/**
 * Copyright Andrew Conway 2012-2013. All rights reserved.
 */
package org.greatcactus.xs.frontend

import org.greatcactus.xs.impl.XSFieldInfo
import scala.collection.mutable.ListBuffer
import java.io.OutputStream
import javax.xml.stream.XMLStreamWriter
import org.greatcactus.xs.api.serialization.XMLSerialize
import java.io.ByteArrayOutputStream
import org.greatcactus.xs.api.serialization.XMLDeserialize
import java.util.Locale
import java.io.ByteArrayInputStream
import org.greatcactus.xs.api.dependency.ExternalDependencyResolver
import org.greatcactus.xs.impl.DependencyInjectionCleaningQueue

/**
 * The master access for editing objects. 
 */

class XSEdit(original:AnyRef,val externalDependencyResolver:Option[ExternalDependencyResolver]) {

  val globalDependencyInjections : Set[AnyRef] = Set.empty

  // methods applying to the tree
  
  val uidsForTreeNodes = new UniqueIDSource()
  val dependencyInjectionCleaningQueue = new DependencyInjectionCleaningQueue()
  
  val treeRoot : XSTreeNode = XSTreeNode(original,this) // children can then be got via node.getChildren
  //def treeChildren(node:XSTree) : IndexedSeq[XSTree] = node.
  /** The result of all the editing */
  def currentObject = treeRoot.getObject
  // methods applying to changes to the tree
  
  private var treeListeners : Set[XSEditListener]= Set.empty
  private var detailsPanes : List[XSDetailsPane[_]] = Nil
  
  
  def addTreeListener(l:XSEditListener) { synchronized {treeListeners+=l; l.setCurrentlyEditing(Option(currentlyEditing))}}
  def removeTreeListener(l:XSEditListener) { synchronized { treeListeners-=l}}
  def addDetailsPane(l:XSDetailsPane[_]) { synchronized {detailsPanes::=l; l.setCurrentlyEditing(Option(currentlyEditing))}}
  def removeDetailsPane(l:XSDetailsPane[_]) { synchronized { detailsPanes=detailsPanes.filter{_!=l}}}
  
  def dispose() { treeRoot.dispose() }
  //
  // debugging
  //
  
  override def toString = {
    val res = new StringBuilder
    def go(node:XSTreeNode,indent:Int) {
      val c1 = if (node eq currentlyEditing) "*" else " "
      val indentation = " "*indent
      val title = node.toString
      res.append(c1+indentation+node.openChar+" "+title+"\n")
      if (node.isOpen) for (kid<-node.treeChildren) go(kid,indent+1)
    }
    go(treeRoot,0)
    if (res.endsWith("\n")) res.setLength(res.length-1)
    res.toString
  }
  
  //
  // methods applying to the field currently being edited
  //
  
  var currentlyEditing : XSTreeNode = treeRoot
  def changeCurrentlyEditing(newNode:XSTreeNode) { 
    synchronized { 
      def checkParentsOpen(node:XSTreeNode) {
        if (node.parent!=null) {
          checkParentsOpen(node.parent)
          setOpen(node.parent,true)
        }
      }
      checkParentsOpen(newNode)
      currentlyEditing = newNode
      for (p<-detailsPanes) p.setCurrentlyEditing(Some(newNode))
      for (t<-treeListeners) t.setCurrentlyEditing(Some(newNode))
    }
  } 
  
  def setOpen(node:XSTreeNode,open:Boolean) {
    synchronized {
      if (node.isOpen!=open) {
        node.isOpen=open
        processChangesToKids(List(new TreeNodeChange(node,node.treeChildren,Nil,Nil)))
      }
    }
  }
  //
  // methods applying to actual editing
  //
  
  /** Delete the whole field */
  def deleteTreeNode(node:XSTreeNode) { deleteTreeNodes(List(node)) }

     /** 
      * Delete the whole field for a set of fields. This has to be done carefully in order
      *  > Anything that is a descendent of something to be deleted can be safely ignored.
      *  > If you are deleting multiple things from one parent, they should all be processed together
      *  > If you are deleting things from a parent p, and also from a child c of p, then you need
      *    to be careful. We will first of all just change the parent objects, but not their parents.
      *    This means the tree data structure is somewhat inconsistent, but it is OK as long as we
      *    process the oldest ones first, as the only problem with the inconsistency is the children
      *    list reevaluation. Then we need to resolve fix up all the ancestor nodes, which is done youngest to oldest.
      **/
  def deleteTreeNodes(nodes:Seq[XSTreeNode]) { synchronized {
    val asSet = nodes.toSet
    if (asSet.contains(treeRoot)) throw new IllegalArgumentException("Cannot delete tree root")
    if (true) { // deal with deletion of currently being edited element - make new currently being edited the first intact parent. 
      var newCurrentlyEditing = currentlyEditing
      while (newCurrentlyEditing.hasSelfOrAncestorInSet(asSet)) newCurrentlyEditing=newCurrentlyEditing.parent
      if (currentlyEditing ne newCurrentlyEditing) changeCurrentlyEditing(newCurrentlyEditing)
    }
    val changes = for ((parent,children)<-nodes.groupBy{_.parent}.toSeq.sortBy{_._1.depth}; if (parent!=null && !parent.hasSelfOrAncestorInSet(asSet))) yield { // don't bother deleting nodes if you are already deleting their parents.
      val newobj = { // needs to deal with the case of subfields of some block becoming part of the block for editing purposes.
        var res = parent.getObject
        for ((fieldInParent,children2)<-children.groupBy{_.fieldInParent}) {
          val indicesToDelete = for (c<-children2) yield c.indexOfFieldInParent
          res=parent.info.deleteFieldAnyRef(res,fieldInParent,indicesToDelete.toSet)
        }
        res
      }
      //val newobj : AnyRef = parent.info.deleteField(parent.obj,node.fieldInParent,node.obj) 
      val changes = parent.changeObject(newobj)
      assert (changes.removedChildren.length==children.filter{!_.fieldInParent.isTableEditable}.length)
      assert (changes.addedChildren.isEmpty)
      changes
    }
    processChangesToKids(changes)
  }}

  
  /** 
   * We have had a set of changes to some nodes. The data structures are now inconsistent, in that 
   * some nodes (the parents in the ChildrenChange elements) have objects that are no longer the children
   * of their parents. We need to fix this. Do this by updating all the ancestors. To prevent multiple changes
   * to one node (which would cause a problem with the inconsistent data structure), 
   * we need to process the deepest ones first.
   * 
   * This function makes the structure consistent (by modifying ancestors), and then tells listeners about
   * the changes once everything is consistent.
   */
  def processChangesToKids(changes:Seq[TreeNodeChange]) { synchronized {
    val fullList = new ListBuffer[TreeNodeChange]
    fullList++=changes
    var haveChangedObject : Set[XSTreeNode] = changes.map{_.parent}(collection.breakOut)
    while (!haveChangedObject.isEmpty) {
      val deepest : Int = haveChangedObject.map{_.depth}.toSeq.max
      val (donow,defer) = haveChangedObject partition {_.depth==deepest}
      haveChangedObject = defer
      if (deepest>0) { // if = 0, then processing parentless node.
        for ((parent,children)<-donow.groupBy{_.parent}) {
          var res = parent.getObject
          for (c<-children) res=parent.info.changeFieldAnyRef(res,c.indexOfFieldInParent,c.fieldInParent,c.getObject)
          val changes = parent.changeObject(res)
          assert (changes.removedChildren.isEmpty)
          assert (changes.addedChildren.isEmpty)
          if (!haveChangedObject.contains(parent)) { haveChangedObject+=parent; fullList+=changes; }
        }
      }
    }
    broadcast(new TreeChange(fullList.toList))
    dependencyInjectionCleaningQueue.cleanReturningInstantlyIfSomeOtherThreadIsAlreadyCleaning()
  }}

  def broadcast(changes:TreeChange) {
    for (l<-treeListeners) l(changes)
    for (p<-detailsPanes) p.refresh(changes)
  }
  
  /**
   * Moving can come from a variety of sources:
   *  (1) Copy/Paste - makes a copy, stuck on end
   *  (2) Cut/Paste - removes old (possibly from different data structure), inserts new stuck on end
   *  (3) Drag 'n drop - possibly removes from original, inserts new stuff at a particular place.
   */

  /**
   * Add a new field "element" of the stated type "field" to the node "parent". If "before" is empty, it will be added at the end.
   * Otherwise it will be added before "before"
   */
  def addField(parent:XSTreeNode,before:Option[XSTreeNode],field:XSFieldInfo,element:AnyRef,executeAfterModificationBeforeRefreshing:Option[()=>Unit]=None) {
    val newobj = parent.info.addFieldAnyRef(parent.getObject,before.map{_.numberOfElementsBeforeThisOneOfGivenType(field)},field,element).asInstanceOf[AnyRef]
    // println("New object in add field "+newobj)
    val changes = parent.changeObject(newobj)
    assert (changes.removedChildren.isEmpty)
    assert (field.isTableEditable || !changes.addedChildren.isEmpty) 
    for (f<-executeAfterModificationBeforeRefreshing) f()
    processChangesToKids(List(changes))
    for (newnode<-changes.addedChildren.find{_.getObject eq element}) changeCurrentlyEditing(newnode)
  }

  /**
   * Set a field to a new value. If the field is a collection, then so should be newValue.
   */
  def setField(parent:XSTreeNode,field:XSFieldInfo,newValue:AnyRef,executeAfterModificationBeforeRefreshing:Option[()=>Unit]) {
    val newobj = parent.info.setFieldAnyRef(parent.getObject,field,newValue)
    val changes = parent.changeObject(newobj)
    for (f<-executeAfterModificationBeforeRefreshing) f()
    processChangesToKids(List(changes))
  }
  
  def copyData(nodes:Seq[XSTreeNode]) : Array[Byte] = {
    val out = new ByteArrayOutputStream
    val writer:XMLStreamWriter = XMLSerialize.outputFactory.createXMLStreamWriter(out,"UTF-8");
    { // write document header
      writer.writeStartDocument("UTF-8","1.0");
      writer.writeCharacters("\n");
	}
    writer.writeStartElement(XMLSerialize.CopiedDataTag) 
    for (n<-nodes) n.serialize(writer)
    writer.writeEndElement();
	writer.writeEndDocument();
	writer.close();
	out.toByteArray()
  }
  
  /**
   * Add a new fields given in the serialized data to the node "parent". If "before" is empty, it will be added at the end.
   * Otherwise it will be added before "before"
   */
  def pasteData(parent:XSTreeNode,data:Array[Byte],before:Option[XSTreeNode]) {
    val loadBefore = for (b<-before) yield (b.fieldInParent,b.numberOfElementsBeforeThisOneOfGivenType(b.fieldInParent))
    val reader = XMLDeserialize.inputFactory.createXMLStreamReader(new ByteArrayInputStream(data),"UTF-8");
    val (newobj,openNodes) = parent.info.deserializeInto(reader,parent.getObject,loadBefore)
    val changes = parent.changeObject(newobj.asInstanceOf[AnyRef])
    for (c<-changes.addedChildren) c.setOpenNodes(openNodes)
    processChangesToKids(List(changes))
  }
  
  def dragData(destination:XSTreeNode,source:Seq[XSTreeNode],before:Option[XSTreeNode]) {
    val asSet = source.toSet
    if (destination.hasSelfOrAncestorInSet(asSet)) throw new IllegalArgumentException("Cannot drag onto self")
    //val safebefore = before match {
    //  case Some(b) if asSet.contains(b) => b.parent.children.dropWhile{_ ne b}.find{!asSet.contains(_)} // get next child not being deleted.
    //  case _ => before
    //}
    val data = copyData(source)
    pasteData(destination,data,before) // this may error. Do this before deleting nodes in case exception gets thrown - deleting but not reinserting would be bad for a user.
    deleteTreeNodes(source)
  }
  
  def getTitle(locale:Locale) : Option[String] = treeRoot.info.textResources(locale).get("PageTitle")

 
    // do this last of all
  
  dependencyInjectionCleaningQueue.cleanReturningInstantlyIfSomeOtherThreadIsAlreadyCleaning()
}

class TreeChange(val elements:Seq[TreeNodeChange]) {
  def contains(node:XSTreeNode) : Boolean = elements.exists{_.parent==node}
  override def toString = elements.mkString(";")
}

trait XSEditListener {
  def apply(changes:TreeChange) : Unit
  def setCurrentlyEditing(node:Option[XSTreeNode])
}