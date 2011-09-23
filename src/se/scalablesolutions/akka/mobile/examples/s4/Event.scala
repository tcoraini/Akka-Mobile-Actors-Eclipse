package se.scalablesolutions.akka.mobile.examples.s4

trait Event[A, B] {
  
  val key: A
  val attribute: B
  
  private var _name = this.getClass.getName
  if (key != null) {
    _name = _name + "##" + key
  }
  private[s4] def uniqueName: String = _name

}
