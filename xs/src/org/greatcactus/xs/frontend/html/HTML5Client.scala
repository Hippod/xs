/**
 * Copyright Andrew Conway 2012-2013. All rights reserved.
 */
package org.greatcactus.xs.frontend.html

import scala.collection.mutable.ListBuffer
import org.greatcactus.xs.frontend.XSEdit
import java.util.Locale
import org.greatcactus.xs.frontend.XSTreeNode
import org.greatcactus.xs.frontend.XSEditListener
import org.greatcactus.xs.frontend.TreeChange
import org.greatcactus.xs.api.icon.Icon
import scala.xml.NodeSeq
import org.greatcactus.xs.api.display.RichLabel

/**
 * The controller for communication with an HTML client. The details of the transport are not
 * included here - it could be via HTTP or Websockets.
 */
class HTML5Client(val xsedit:XSEdit,val locale:Locale) {

  private[this] val cmdBuffer = new ClientMessageBuffer
  
  val session = new HTTPSession(new HTTPSessionWorker{
    override def receivedMessage(message:SimpleClientMessage) { process(message) }
  })
  val transport = new HTMLTransport {
    def sendMessageWork(message:ClientMessage) { session.addMessage(message) }
  }
  val detailsPane = new HTML5DetailsPane(this)
  xsedit.addDetailsPane(detailsPane)
  val treeModel = new TreeModel[XSTreeNode] {
      def children(node:XSTreeNode) : Seq[XSTreeNode] = node.treeChildren
      def title(node:XSTreeNode) : RichLabel = node.label(locale)
      def icon(node:XSTreeNode) : Option[String] = for (icon<-node.icon; ri <-icon.getInstance(Icon.html5formats,Some(16))) yield ri.url
      def userToggledStatus(node:XSTreeNode,isNowOpen:Boolean) { xsedit.setOpen(node,isNowOpen)}
      def userSelected(node:XSTreeNode) { xsedit.changeCurrentlyEditing(node); }
      def uniqueID(node:XSTreeNode) : String = node.uid.toString
      def isOpen(node:XSTreeNode) : Boolean = node.isOpen
      def dragLocal(source:XSTreeNode,dest:XSTreeNode,isAbove:Boolean) {
        val above = isAbove && dest.parent!=null
        xsedit.dragData(if (above) dest.parent else dest,List(source),if (above) Some(dest) else None)
      } 
      def errorLevel(node:XSTreeNode) : Int = node.worstErrorLevel  
      var clipboard : Option[Array[Byte]] = None // TODO allow non local copy/paste
      def userContextMenu(command:String,nodes:Seq[XSTreeNode]) {
        command match {
          case "copy" => clipboard=Some(xsedit.copyData(nodes))
          case "cut" => clipboard=Some(xsedit.copyData(nodes)); xsedit.deleteTreeNodes(nodes)
          case "paste" => for (c<-clipboard;node<-nodes.headOption) xsedit.pasteData(node, c, None)
          case "erase" => xsedit.deleteTreeNodes(nodes)
        }
      }
  }
  val treePane = new HTML5Tree(locale,transport,treeModel,xsedit.treeRoot,session.sessionPrefix,session.id) 
  xsedit.addTreeListener(new XSEditListener() {
      def apply(changes:TreeChange) { 
        //println("Got tree changes")
        for (e<-changes.elements) if (!e.parent.isTableLine) treePane.refresh(e.parent)
        flushMessages()
      }
      def setCurrentlyEditing(node:Option[XSTreeNode]) { 
        treePane.setSelected(node)
        flushMessages()
      }
  })
  
  
  
  private def process(message:SimpleClientMessage) {
    try {
      transport.startBuffering();
      val args = message.args
      message.command match {
        case "Action" if args.length==2 =>
          //println("Got action command "+args(0))
          //println("for context "+args(1)+" expecting "+detailsPane.nodeIDCurrentlyBeingEdited)
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(1)) detailsPane.uiActivated(args(0)); 
        case "Change" if args.length==3||args.length==5 =>
          //println("Got change command "+args(0)+" "+args(1))
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(2)) {
            val id = args(0)
            val newValue = args(1)
            val gridInd = id.lastIndexOf("_grid")
            if (args.length==5 && !args(3).isEmpty() && !args(4).isEmpty && gridInd!= -1) detailsPane.uiChangeGrid(id.substring(0,gridInd),args(3).toInt,args(4),newValue)
            else detailsPane.uiChangedTextField(id,newValue,true); 
          }
        case "ChangeCB" if args.length==3 =>
          //println("Got change command "+args(0)+" "+args(1))
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(2)) detailsPane.uiChangedBooleanField(args(0),args(1).toBoolean); 
        case "KeyPress" if args.length==3 =>
          //println("Got keypress command "+args(0))
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(2)) detailsPane.uiChangedTextField(args(0),args(1),false); 
        case "GridDnD" if args.length==4 =>
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(3)) {
            detailsPane.uiDragGridRows(args(0),args(1).split(',').map{_.toInt},args(2).toInt)
          }
        case "TreeOpen" if args.length==2 =>
          //println("Got tree open command "+args(0))
          treePane.clickedOnOpener(args(0),Some(args(1).toBoolean)); 
        case "TreeSelect" if args.length==1 =>
          //println("Got tree select "+args(0))
          treePane.clickedOnNode(args(0)); 
        case "TreeContextMenu" if args.length==2 =>
          treePane.contextMenu(args(0),args(1).split(','))
        case "TableContextMenu" if args.length==4 =>
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(3)) {
            detailsPane.uiTableContextMenu(args(0),args(1),args(2).split(',').map{_.toInt})          
          }
        case "DragLocal" if args.length==3 =>
          treePane.dragLocal(args(0),args(1),args(2).toBoolean)
        case "Edited" if args.length==2 => detailsPane.uiChangedTextField(args(0),args(1),true)
        case "PartialEdit" if args.length==2 => detailsPane.uiChangedTextField(args(0),args(1),false)
        case "NewRowOnGrid" if args.length==4 => 
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(3)) detailsPane.uiNewRowOnGrid(args(0),args(1),args(2))
        case "ChangeGrid" if args.length==5 => 
          if (detailsPane.nodeIDCurrentlyBeingEdited==args(4)) detailsPane.uiChangeGrid(args(0),args(1).toInt,args(2),args(3))
        case _ => println("Received unanticipated command "+message.command+"("+args.mkString(",")+")")
      }
    } catch { 
      case e:Exception => e.printStackTrace() // don't crash any event thread, etc. 
    } finally { 
      transport.endBuffering() 
    }
  }
  
  private def messageOfBuffer(buffer:ListBuffer[ClientMessage]): Option[ClientMessage] = {
      val cmds = buffer.toList
      buffer.clear()
      cmds match {
        case Nil => None
        case h::Nil => Some(h)
        case l => Some(new MultipleClientMessage(l))
      }  
  }
  
  
  def sendMessage(msg:ClientMessage) { queueMessage(msg); flushMessages() }
  def queueMessage(msg:ClientMessage) { 
    cmdBuffer+=msg
   // println("Message "+msg)
  }
  def flushMessages() {
    //println("FlushMessages()")
    for (m<-cmdBuffer.get()) transport.sendMessage(m)
  }

  def baseHTML = 
    <div class="xsEdit">
       {treePane.baseHTML}
       { detailsPane.baseHTML }
       { session.createSessionHTML }
    </div>
  
  
}

abstract class HTMLTransport {
  private[this] val buffer = new ClientMessageBuffer
  private[this] var ifZeroThenDontBuffer = 0
  def startBuffering() { synchronized { ifZeroThenDontBuffer+=1 }}
  def endBuffering() { 
    synchronized { 
      ifZeroThenDontBuffer-=1
      if (ifZeroThenDontBuffer==0) for (m<-buffer.get()) sendMessageWork(m)
    }
  }
  
  def sendMessage(message:ClientMessage) {
     synchronized { 
         if (ifZeroThenDontBuffer==0) sendMessageWork(message)
         else buffer+=message
     }
  }

  /** Should be implemented by the client */
  def sendMessageWork(message:ClientMessage)
}