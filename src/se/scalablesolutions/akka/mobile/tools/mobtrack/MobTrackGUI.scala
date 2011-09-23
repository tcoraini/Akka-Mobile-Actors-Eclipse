package se.scalablesolutions.akka.mobile.tools.mobtrack

import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.theater.TheaterDescription
import se.scalablesolutions.akka.mobile.theater.TheaterNode

import collection.mutable.HashMap

import swing._
import event._

import java.awt.Color
import java.awt.Rectangle
import java.awt.Dimension
import java.awt.Font

object MobTrackGUI extends SimpleSwingApplication {
  private val nodePanels = new HashMap[TheaterNode, NodePanel]

  def top = new MainFrame {
    val nodes = ClusterConfiguration.nodes.values

    val mainPanel = new FlowPanel(FlowPanel.Alignment.Left)() {
      for (nodeInfo <- nodes) {
	val newNodePanel = createNodePanel(nodeInfo, nodePanelSize(ClusterConfiguration.numberOfNodes))
	nodePanels += ((nodeInfo.node, newNodePanel))
	contents += newNodePanel
      }
      preferredSize = 
	if (ClusterConfiguration.numberOfNodes <= 9)
	  new Dimension(1000, 615)
	else {
	  val rows = (ClusterConfiguration.numberOfNodes / 3d).ceil.toInt
	  new Dimension(1000, 205 * rows)
	}
    }
    
    contents = new ScrollPane(mainPanel)
    title = "MobTrack - Mobile Actors Tracking System"
    size = new Dimension(1030, 650)
//    preferredSize = new Dimension(1100, 615)
    location = new Point(100, 100)
  }

  def arrive(uuid: String, node: TheaterNode, fromMigration: Boolean = false) {
    nodePanels.get(node) match {
      case Some(nodePanel) => 
	nodePanel.arrive(uuid, fromMigration)
      case None => ()
    }
  }

  def depart(uuid: String, node: TheaterNode, afterMigration: Boolean = false): Boolean = {
    nodePanels.get(node) match {
      case Some(nodePanel) => nodePanel.depart(uuid, afterMigration)
      case None => false
    }
  }

  def migrate(uuid: String, from: TheaterNode, to: TheaterNode) {
    depart(uuid, from, true)
    arrive(uuid, to, true)
  }

  private def createNodePanel(nodeInfo: TheaterDescription, size: Dimension): NodePanel = {
    new NodePanel(nodeInfo, size)
  }

  private def nodePanelSize(numberOfNodes: Int): Dimension = {
    if (numberOfNodes <= 3)
      new Dimension((1000 / numberOfNodes).toInt, 600)
    else if (numberOfNodes == 4)
      new Dimension(500, 300)
    else if (numberOfNodes <= 6)
      new Dimension(333 /* 1000 / 3 */, 300 /* 600 / 2 */)
    else
      new Dimension(333, 200)
  }
}

class NodePanel(nodeInfo: TheaterDescription, size: Dimension) extends BoxPanel(Orientation.Vertical) {
  val actorComponents = new HashMap[String, ActorComponent]
  
  val node = nodeInfo.node
  
  val label = new Label { 
    val baseText = "[" + node.hostname + ":" + node.port + "]" 
    
    updateText(0)

    def updateText(numberOfActors: Int): Unit = {
      text = 
	if (numberOfActors == 0)
	  baseText + " - no actors"
	else
	  baseText + " - " + numberOfActors + " actor(s)"
      revalidate()
    }

    xLayoutAlignment = 0.5
    font = new Font(Font.SANS_SERIF, Font.BOLD, 16)
    if (nodeInfo.hasNameServer)
      foreground = Color.blue
  }

  val actorsPanel = new FlowPanel(FlowPanel.Alignment.Left)()

  contents += label
  contents += actorsPanel
  border = Swing.LineBorder(Color.black, 3)

  preferredSize = size

  def arrive(uuid: String, fromMigration: Boolean) {
    if (!actorComponents.contains(uuid)) {
      val component = new ActorComponent(uuid)
      actorComponents.put(uuid, component)
      label.updateText(actorComponents.size)
      if (fromMigration)
	drawActorArrived(component, actorsPanel)
      else
	drawActorStarted(component, actorsPanel)
    }
  }

  def depart(uuid: String, afterMigration: Boolean): Boolean = {
    actorComponents.get(uuid) match {
      case Some(component) =>
	actorComponents -= uuid
        label.updateText(actorComponents.size)
        if (afterMigration)
	  drawActorLeft(component, actorsPanel)
        else
	  drawActorStopped(component, actorsPanel)
	true
      
      case None => false
    }
  }
  
  private def drawActorStarted(actor: ActorComponent, panel: Panel): Unit = {
    new Thread {
      override def run() {
	panel.peer.add(actor.peer)

	actor.color = new Color(0, 255, 0, 255)
	actor.repaint()
	panel.revalidate()

	Thread.sleep(1000)

	actor.color = new Color(255, 0, 0, 255)	
	actor.repaint()
	panel.revalidate()

      }
    } start()
  }

  private def drawActorArrived(actor: ActorComponent, panel: Panel): Unit = {
    new Thread {
      override def run() {
	panel.peer.add(actor.peer)
	var alpha = 0.0
	while (alpha <= 1.0) {
	  actor.color = new Color(255, 0, 0, (255 * alpha).toInt)
	  alpha = alpha + 0.2
	  actor.repaint()
	  panel.revalidate()
	  Thread.sleep(200)
	}
      }
    } start()
  }
  
  private def drawActorLeft(actor: ActorComponent, panel: Panel): Unit = {
    new Thread {
      override def run() {
	var alpha = 1.0
	while (alpha > 0.0) {
	  actor.color = new Color(255, 0, 0, (255 * alpha).toInt)
	  alpha = alpha - 0.2
	  actor.repaint()
	  panel.revalidate()
	  Thread.sleep(200)
	}
	panel.peer.remove(actor.peer)
	if (panel.peer.getComponentCount == 0) {
	  panel.peer.updateUI()
	}
	panel.revalidate()
      }
    } start()
  }

  private def drawActorStopped(actor: ActorComponent, panel: Panel): Unit = {
    new Thread {
      override def run() {
	actor.color = new Color(128, 128, 128, 255)
	actor.repaint()
	panel.revalidate()

	Thread.sleep(1000)

	panel.peer.remove(actor.peer)
	if (panel.peer.getComponentCount == 0) {
	  panel.peer.updateUI()
	}
	panel.revalidate()
	
      } 
    } start()
  }
}

class ActorComponent(val uuid: String) extends Component {
  var color = new Color(255, 0, 0, 255)

  override def paint(g: Graphics2D) {
    g.setColor(color)
    g.drawOval(2, 2, 10, 10)
    g.fillOval(2, 2, 10, 10)
  }

  preferredSize = new Dimension(14, 14)
  
  tooltip = uuid

  override def equals(other: Any) = other match {
    case ac: ActorComponent => ac.uuid == uuid
    case _ => false
  }

  override def hashCode = uuid.hashCode

  override def toString = "[ActorComponent for " + uuid + "]"
}

  
  
  
			 


