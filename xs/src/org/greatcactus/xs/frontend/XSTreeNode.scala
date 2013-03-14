/**
 * Copyright Andrew Conway 2012-2013. All rights reserved.
 */
package org.greatcactus.xs.frontend

import org.greatcactus.xs.impl.SerializableTypeInfo
import org.greatcactus.xs.impl.XSFieldInfo
import org.greatcactus.xs.api.errors.XSError
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer
import javax.xml.stream.XMLStreamWriter
import org.greatcactus.xs.api.serialization.XMLSerialize
import org.greatcactus.xs.util.EqualityByPointerEquality
import scala.collection.mutable.SetBuilder
import org.greatcactus.xs.util.EqualityByPointerEquality
import org.greatcactus.xs.api.icon.Icon
import org.greatcactus.xs.impl.DependencyInjectionCurrentStatus
import org.greatcactus.xs.impl.DependencyInjectionFunction
import java.util.Locale
import org.greatcactus.xs.api.display.RichLabel
import org.greatcactus.xs.api.errors.ResolvedXSError
import org.greatcactus.xs.api.icon.ConcreteIcon
import org.greatcactus.xs.impl.TrimInfo
import org.greatcactus.xs.util.InvalidatableCache

/**
 * Contain information about the hierarchical structure of an XS object, suitable for displaying in a JTree or similar.
 * 
 * <p>This mutable structure contains the tree representation of the actual immutable object. The idea
 * is that editing changes the object significantly, but as much of the tree is left intact as possible,
 * so that GUIs don't have things closing unexpectedly. This also keeps track of dependency injections and
 * errors.</p>
 * 
 * The basic operations that can be done during an edit are
 *   -> change a field (which will propagate through to its parents)
 *   -> move a field to elsewhere in the tree (it does not bother to try to keep track of the open status of the thing being moved)
 *   -> delete or insert a field
 */
class XSTreeNode(
    /** Null if there is no parent */
    val parent:XSTreeNode,
    val info : SerializableTypeInfo[_], 
    /** What field this element is in the parent */
    val fieldInParent : XSFieldInfo,
    /** The object at this level */
    private[this] var obj : AnyRef,
    val xsedit:XSEdit
    ) {

  //private var obj : AnyRef=null
  /** Unique amongst all nodes for a given edit structure */
  val uid : Long = xsedit.uidsForTreeNodes.newID()
  def injectionNodesFromParent : Set[AnyRef] = if (parent==null) xsedit.globalDependencyInjections else parent.dependencyInjection.dependenciesToPropagateToChildren(info.dependencyInjectionInfo.fromParentDependencyInfo)
  private[xs] val dependencyInjection = new DependencyInjectionCurrentStatus(info.dependencyInjectionInfo,this)
  dependencyInjection.changedObject(null,obj)
  private[this] var disposed=false
  var isOpen : Boolean = if (fieldInParent==null) true else fieldInParent.isExpandOnFirstDisplay
  
  def isRoot = parent==null
  val depth:Int = if (parent==null) 0 else 1+parent.depth
  private[this] var treeChildrenV : IndexedSeq[XSTreeNode] = getTreeChildren(Nil).children
  private[this] var tableChildrenV : Map[XSFieldInfo,IndexedSeq[XSTreeNode]] = getTableChildren(Map.empty++(info.tableNodeFields.map{_ -> IndexedSeq.empty}))
  def treeChildren : IndexedSeq[XSTreeNode] = treeChildrenV
  def tableChildren : Map[XSFieldInfo,IndexedSeq[XSTreeNode]] = tableChildrenV
  def allChildren : IndexedSeq[XSTreeNode] = treeChildrenV++tableChildren.values.flatten
  def root : XSTreeNode = if (parent==null) this else parent.root
  /** If this is a table line, rather than a tree line */
  private var tableLine : Option[(XSFieldInfo,Int)] = None
  def isTableLine : Boolean = tableLine.isDefined
  def getTableLine : Option[(XSFieldInfo,Int)] = tableLine
  
  def getTreeChildren(oldTreeChildren:Seq[XSTreeNode]) : TreeNodeChange = getChildren(info.treeNodeFields,oldTreeChildren)
  def getTableChildren(oldTableChildren:Map[XSFieldInfo,IndexedSeq[XSTreeNode]]) : Map[XSFieldInfo,IndexedSeq[XSTreeNode]] = {
    for ((tableField,oldkids)<-oldTableChildren) yield {
      val newkids = getChildren(List(tableField),oldkids).children
      for (i<-0 until newkids.length) newkids(i).tableLine=Some((tableField,i))
      (tableField,newkids)
    }
  }
    
  
  private def getChildren(fields:Seq[XSFieldInfo],oldChildren:Seq[XSTreeNode]) : TreeNodeChange = {
    val allChildren = new ArrayBuffer[XSTreeNode]
    val addedChildren = new ListBuffer[XSTreeNode]
    var childrenMap : Map[EqualityByPointerEquality[AnyRef],XSTreeNode]= Map.empty++(for (c<-oldChildren) yield new EqualityByPointerEquality(c.getObject)->c)
    // children match by equality of the obj elements
    for (blockField<-fields) {
      for (elem<-blockField.getAllFieldElements(obj)) if (elem!=null) { // At some point I may want to include a feature such that some classes can be edited inilne as part of their parents. In this case we wouldn't quite want blocks - we want things that show up as children. So must be @XS and should also include subfields which are included at the same level.
        val holder = new EqualityByPointerEquality(elem.asInstanceOf[AnyRef])
        childrenMap.get(holder) match {
          case Some(node) => allChildren+=node; childrenMap-=holder;
          case None => // not already there.
            XSTreeNode.apply(elem.asInstanceOf[AnyRef],this,blockField,xsedit) match {
              case Some(node) => allChildren+=node; addedChildren+=node
              case None =>
            }
        }      
      }
    }
    new TreeNodeChange(this,allChildren.toIndexedSeq,childrenMap.values.toSeq,addedChildren.toSeq)
  }
  
  /** If this is the nth element of a given field in the parent, return n (0 based). */
  private[frontend] def indexOfFieldInParent : Int = numberOfElementsBeforeThisOneOfGivenType(fieldInParent)

  /** Get the number of elements of the stated type that occur before this element in the tree.  */
  private[frontend] def numberOfElementsBeforeThisOneOfGivenType(field:XSFieldInfo) : Int = {
    if (parent==null) 0
    else {
      var res = 0
      for (n<-if (field.isTableEditable) parent.tableChildren(field) else parent.treeChildren) {
        if (n eq this) return res
        else if (n.fieldInParent==field) res+=1
      }
      res
    }
  }
  
  def hasSelfOrAncestorInSet(set:Set[XSTreeNode]) : Boolean = {
    if (set.contains(this)) true
    else if (parent==null) false
    else parent.hasSelfOrAncestorInSet(set)
  }
  

  /** 
   * Child objects should be changed before the parent objects. Otherwise when the class tries to match child objects
   * to existing child XSTreeNode elements, it will unnecessarily discard and recreate new ones.
   **/
  def changeObject(newobj : AnyRef) : TreeNodeChange = { 
    synchronized {
      uniquenessCheckResolution.invalidate()
      tableFieldsCache.clear()
      worstErrorLevelCache.invalidate()
      dependencyInjection.changedObject(obj,newobj)
      obj = newobj
      val kids = getTreeChildren(treeChildren)
      kids.disposeRemoved()
      treeChildrenV = kids.children
      tableChildrenV = getTableChildren(tableChildrenV)
      kids
    }
  }
    
  /** Disposal needs to be done to dispose of the listeners in the dependencyInjection structure */
  def dispose() {
    if (disposed) return // could possible throw an error, at least in dev mode.
    disposed=true
    for (c<-allChildren) c.dispose()
    dependencyInjection.dispose()
  }
  
  def cleanDependencies() {
    if (disposed) return
    uniquenessCheckResolution.clean()
   // println("In cleanDependencies for "+this)
    if (dependencyInjection.clean()) { // need to refresh this node on clients.
      tableFieldsCache.clear()
      worstErrorLevelCache.invalidate()
      if (parent!=null) parent.childHadWorstErrorLevelRecomputed()
      //println("Boadcasting clean event for "+this)
      xsedit.broadcast(new TreeChange(List(new TreeNodeChange(this,treeChildren,Nil,Nil))))
    }
  }
  
  private def childHadWorstErrorLevelRecomputed() {
    val changed = worstErrorLevelCache.synchronized {
       val existing = worstErrorLevelCache.get
       worstErrorLevelCache.invalidate()
       val newRes = worstErrorLevelCache.get
       existing!=newRes 
    }
    if (changed) {
      xsedit.broadcast(new TreeChange(List(new TreeNodeChange(this,treeChildren,Nil,Nil))))
      if (parent!=null) parent.childHadWorstErrorLevelRecomputed()
    }
  }
  
  private def getAllOpenNodes(openTags:ListBuffer[EqualityByPointerEquality[AnyRef]]) {
    if (isOpen) openTags+=new EqualityByPointerEquality(obj)
    for (c<-treeChildren) c.getAllOpenNodes(openTags)
  } 
  private[frontend] def setOpenNodes(openTags:Set[EqualityByPointerEquality[AnyRef]]) {
    isOpen = openTags.contains(new EqualityByPointerEquality(obj))
    for (c<-treeChildren) c.setOpenNodes(openTags)
  }
  
  /** Serialize this fragment to the writer */
  def serialize(writer:XMLStreamWriter) {
    val openTags = new ListBuffer[EqualityByPointerEquality[AnyRef]]
    getAllOpenNodes(openTags)
    XMLSerialize.serialize(obj,writer,info,if (fieldInParent==null) None else fieldInParent.overridingName,openTags.toSet)
  }
  
  def label(locale:Locale):RichLabel = RichLabel(dependencyInjection.getLabel,obj.toString,locale)
  
  private val iconFromDependencies : AnyRef=>Option[Icon] = {
    case s:String => info.iconSource.iconOfLogicalName(s)
    case i:Icon => Some(i)
    case i:ConcreteIcon => Some(new Icon("",List(i)))
    case _ => None    
  }
  
  def icon:Option[Icon] = dependencyInjection.getIconSpec.flatMap{iconFromDependencies}.orElse(info.icon)
  
  /** Get the icon for the field from dependency injection */
  def specialIconForField(fieldname:String) : Option[Icon] = dependencyInjection.getIconSpecForField(fieldname).flatMap{iconFromDependencies}
  
  /** Get the label for the field from dependency injection */
  def specialLabelForField(fieldname:String,locale:Locale):Option[RichLabel] = dependencyInjection.getLabelForField(fieldname).flatMap{RichLabel(_,locale)}
  def errors(fieldname:String,locale:Locale,humanEdited:Array[Option[TrimInfo]]) : List[ResolvedXSError] = {
    lazy val collectionLengths = for (field<-info.fields.find(_.name==fieldname);col<-field.getFieldAsStringCollectionLengthInfo(obj,humanEdited)) yield col 
    dependencyInjection.getErrors(fieldname).map{_.resolve(locale, collectionLengths)}
  }
  private[this] val worstErrorLevelCache = new InvalidatableCache[Int](
      allChildren.foldLeft(dependencyInjection.worstErrorLevel)((e,n)=>e.min(n.worstErrorLevel))
    
  )
  def worstErrorLevel : Int = worstErrorLevelCache.get
  
  def getPseudoField(function:DependencyInjectionFunction,locale:Locale) : RichLabel = RichLabel(dependencyInjection.getFunctionResult(function),"",locale)
  
  private[this] val tableFieldsCache =new collection.mutable.HashMap[ColumnExtractors,IndexedSeq[String]]
  
  def getTableFields(extractor:ColumnExtractors) : IndexedSeq[String] = tableFieldsCache.getOrElseUpdate(extractor,extractor.extract(this))
  
  def mayDelete = parent!=null && fieldInParent.isCollectionOrArray
  def isEnabled(field:String) = dependencyInjection.isEnabled(field)
  def isVisible(field:String) = dependencyInjection.isVisible(field)
  def canAdd(field:XSFieldInfo) = field.maxChildren match {
    case -1 => true
    case n =>
      val existing = field.getAllFieldElements(getObject).size
      // println("canAdd: Field "+field.name+" n="+n+" existing="+existing)
      existing < n
  }

  

  def openChar : String = if (treeChildren.isEmpty) "." else if (isOpen) "-" else "+"
  def toFullTreeString(indent:Int,locale:Locale):String = {
    val self = " "*indent+openChar+" "+label(locale).text
    val kids = treeChildren.map{_.toFullTreeString(indent+1,locale)}
    (self::kids.toList).mkString("\n")
  } 
  override def toString = obj.toString
  
  private[xs] def getObject = obj
  
  //
  // code for handling uniqueness annotations
  //
  
  val uniquenessCheckResolution = new org.greatcactus.xs.impl.UniquenessCheckResolution(this,info.uniquenessCheckLocal,info.uniquenessCheckParent,info.uniquenessCheckGlobal)
  def parentUniquenessErrorsChanged() {
    //println("parentUniquenessErrorsChanged for "+this)
    if (!uniquenessCheckResolution.parent.isEmpty) { worstErrorLevelCache.invalidate(); dependencyInjection.changedUniquenessValues(uniquenessCheckResolution.parent) }
  }
  
  def globalUniquenessErrorsChanged() {
    //println("globalUniquenessErrorsChanged for "+this)
    if (!uniquenessCheckResolution.global.isEmpty)  { worstErrorLevelCache.invalidate(); dependencyInjection.changedUniquenessValues(uniquenessCheckResolution.global) }
    for (c<-allChildren) c.globalUniquenessErrorsChanged()
  }
  
  
  xsedit.dependencyInjectionCleaningQueue.add(this)
}

class TreeNodeChange(val parent:XSTreeNode,val children:IndexedSeq[XSTreeNode],val removedChildren:Seq[XSTreeNode],val addedChildren:Seq[XSTreeNode]) {
  def changedStructure = !(removedChildren.isEmpty&&addedChildren.isEmpty)
  
  private[xs] def disposeRemoved() { for (gone<-removedChildren) gone.dispose() }
  override def toString = parent.toString
}

//class ErrorInTree(val error:XSError,val field:Option[XSFieldInfo])

// class PathToParent(val path:List[XSTree])

object XSTreeNode {
  /** Get an XSTree for the given object, which should be @XS */
  def apply(obj:AnyRef,xsedit:XSEdit) : XSTreeNode = {
     apply(obj,null,null,xsedit).getOrElse(throw new IllegalArgumentException("Object class "+obj.getClass+" is not serializable by XS"))
  }
  
  private def apply(obj:AnyRef,parent:XSTreeNode,fieldInParent:XSFieldInfo,xsedit:XSEdit) : Option[XSTreeNode] = {
    for (info<-SerializableTypeInfo.get(obj.getClass)) yield {
      val res = new XSTreeNode(parent,info,fieldInParent,obj,xsedit)
      //res.treeChildren = for (blockField<-info.fieldsAsBlocks;elem<-blockField.getAllFieldElements(obj);subtree<-apply(elem.asInstanceOf[AnyRef],res,blockField)) yield subtree
      res
    }
  }
}
