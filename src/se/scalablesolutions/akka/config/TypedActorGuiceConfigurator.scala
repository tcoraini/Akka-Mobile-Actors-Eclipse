/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.util.Logging

import org.codehaus.aspectwerkz.proxy.Proxy

import scala.collection.mutable.HashMap

import java.net.InetSocketAddress
import java.lang.reflect.Method

import com.google.inject._

/**
 * This is an class for internal usage. Instead use the <code>se.scalablesolutions.akka.config.TypedActorConfigurator</code>
 * class for creating TypedActors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class TypedActorGuiceConfigurator extends TypedActorConfiguratorBase with Logging {
  private var injector: Injector = _
  private var supervisor: Option[Supervisor]  = None
  private var restartStrategy: RestartStrategy  = _
  private var components: List[Component] = _
  private var supervised: List[Supervise] = Nil
  private var bindings: List[DependencyBinding] = Nil
  private var configRegistry = new HashMap[Class[_], Component] // TODO is configRegistry needed?
  private var typedActorRegistry = new HashMap[Class[_], Tuple3[AnyRef, AnyRef, Component]]
  private var modules = new java.util.ArrayList[Module]
  private var methodToUriRegistry = new HashMap[Method, String]

  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the typed actor
   * @return the typed actors for the class
   */
  def getInstance[T](clazz: Class[T]): List[T] = synchronized {
    log.debug("Retrieving typed actor [%s]", clazz.getName)
    if (injector eq null) throw new IllegalActorStateException(
      "inject() and/or supervise() must be called before invoking getInstance(clazz)")
    val (proxy, targetInstance, component) =
        typedActorRegistry.getOrElse(clazz, throw new IllegalActorStateException(
          "Class [" + clazz.getName + "] has not been put under supervision" +
          "\n(by passing in the config to the 'configure' and then invoking 'supervise') method"))
    injector.injectMembers(targetInstance)
    List(proxy.asInstanceOf[T])
  }

  def isDefined(clazz: Class[_]): Boolean = synchronized {
    typedActorRegistry.get(clazz).isDefined
  }

  override def getExternalDependency[T](clazz: Class[T]): T = synchronized {
    injector.getInstance(clazz).asInstanceOf[T]
  }

  def getComponentInterfaces: List[Class[_]] =
    for (c <- components) yield {
      if (c.intf.isDefined) c.intf.get
      else c.target
    }

  override def configure(restartStrategy: RestartStrategy, components: List[Component]):
    TypedActorConfiguratorBase = synchronized {
    this.restartStrategy = restartStrategy
    this.components = components.toArray.toList.asInstanceOf[List[Component]]
    bindings = for (component <- this.components) yield {
      newDelegatingProxy(component)
//      if (component.intf.isDefined) newDelegatingProxy(component)
//      else newSubclassingProxy(component)
    }
    val deps = new java.util.ArrayList[DependencyBinding](bindings.size)
    for (b <- bindings) deps.add(b)
    modules.add(new TypedActorGuiceModule(deps))
    this
  }

/*
  private def newSubclassingProxy(component: Component): DependencyBinding = {
    val targetClass =
      if (component.target.isInstanceOf[Class[_ <: TypedActor]]) component.target.asInstanceOf[Class[_ <: TypedActor]]
      else throw new IllegalArgumentException("TypedActor [" + component.target.getName + "] must be a subclass of TypedActor")
    val actorRef = Actor.actorOf(new Dispatcher(component.transactionRequired))
    if (component.dispatcher.isDefined) actorRef.dispatcher = component.dispatcher.get
    val remoteAddress =
      if (component.remoteAddress.isDefined)
        Some(new InetSocketAddress(component.remoteAddress.get.hostname, component.remoteAddress.get.port))
      else None
    val proxy = TypedActor.newInstance(targetClass, actorRef, remoteAddress, component.timeout).asInstanceOf[AnyRef]
    remoteAddress.foreach(address => RemoteServer.registerTypedActor(address, targetClass.getName, proxy))
    supervised ::= Supervise(actorRef, component.lifeCycle)
    typedActorRegistry.put(targetClass, (proxy, proxy, component))
    new DependencyBinding(targetClass, proxy)
  }
*/
  private def newDelegatingProxy(component: Component): DependencyBinding = {
    component.target.getConstructor(Array[Class[_]](): _*).setAccessible(true)
    val interfaceClass = if (component.intf.isDefined) component.intf.get
                         else throw new IllegalActorStateException("No interface for TypedActor specified")
    val implementationClass = component.target
    val timeout = component.timeout

    val actorRef = Actor.actorOf(TypedActor.newTypedActor(implementationClass))
    actorRef.timeout = timeout
    if (component.dispatcher.isDefined) actorRef.dispatcher = component.dispatcher.get
    val typedActor = actorRef.actorInstance.get.asInstanceOf[TypedActor]

    val proxy = Proxy.newInstance(Array(interfaceClass), Array(typedActor), true, false)

    val remoteAddress =
      if (component.remoteAddress.isDefined)
        Some(new InetSocketAddress(component.remoteAddress.get.hostname, component.remoteAddress.get.port))
      else None

    remoteAddress.foreach { address =>
      actorRef.makeRemote(remoteAddress.get)
      RemoteServer.registerTypedActor(address, implementationClass.getName, proxy)
    }

    AspectInitRegistry.register(
      proxy,
      AspectInit(interfaceClass, typedActor, actorRef, remoteAddress, timeout))
    typedActor.initialize(proxy)
    actorRef.start

    supervised ::= Supervise(actorRef, component.lifeCycle)

    typedActorRegistry.put(interfaceClass, (proxy, typedActor, component))
    new DependencyBinding(interfaceClass, proxy)
  }

  override def inject: TypedActorConfiguratorBase = synchronized {
    if (injector ne null) throw new IllegalActorStateException("inject() has already been called on this configurator")
    injector = Guice.createInjector(modules)
    this
  }

  override def supervise: TypedActorConfiguratorBase = synchronized {
    if (injector eq null) inject
    supervisor = Some(TypedActor.supervise(restartStrategy, supervised))
    this
  }

  /**
   * Add additional services to be wired in.
   * <pre>
   * typedActorConfigurator.addExternalGuiceModule(new AbstractModule {
   *   protected void configure() {
   *     bind(Foo.class).to(FooImpl.class).in(Scopes.SINGLETON);
   *     bind(BarImpl.class);
   *     link(Bar.class).to(BarImpl.class);
   *     bindConstant(named("port")).to(8080);
   *   }})
   * </pre>
   */
  def addExternalGuiceModule(module: Module): TypedActorConfiguratorBase  = synchronized {
    modules.add(module)
    this
  }

  def getGuiceModules: java.util.List[Module] = modules

  def reset = synchronized {
    modules = new java.util.ArrayList[Module]
    configRegistry = new HashMap[Class[_], Component]
    typedActorRegistry = new HashMap[Class[_], Tuple3[AnyRef, AnyRef, Component]]
    methodToUriRegistry = new HashMap[Method, String]
    injector = null
    restartStrategy = null
  }

  def stop = synchronized {
    if (supervisor.isDefined) supervisor.get.shutdown
  }
}

