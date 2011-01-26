package scala.tools.nsc
package interactive


import java.io.{ PrintWriter, StringWriter , FileReader, FileWriter }
import _root_.scala.collection.mutable.{ArrayBuffer, SynchronizedBuffer}

//BACK-2.8.0 use absolute import to avoid wrong search with relative
import _root_.scala.collection.mutable
import mutable.{LinkedHashMap, SynchronizedMap,LinkedHashSet, SynchronizedSet}
import _root_.scala.concurrent.SyncVar
import _root_.scala.util.control.ControlThrowable
import _root_.scala.tools.nsc.io.AbstractFile
import _root_.scala.tools.nsc.util.{SourceFile, Position, RangePosition, NoPosition}
import _root_.scala.tools.nsc.reporters._
import _root_.scala.tools.nsc.symtab._
import _root_.scala.tools.nsc.ast._
import scala.tools.nsc.interactive.io.{Pickler, LogReplay, Logger, NullLogger, Replayer }

/** The main class of the presentation compiler in an interactive environment such as an IDE
 */
class Global(settings: Settings, reporter: Reporter) 
  extends _root_.scala.tools.nsc.Global(settings, reporter) 
     with CompilerControl 
     with RangePositions
     with ContextTrees 
     with RichCompilationUnits 
     with Picklers { 
self =>

  import definitions._

  val debugIDE = false
  //BACK-2.8 verboseIDE
  var verboseIDE = true

  private def replayName = "" //BACK-2.8 settings.YpresentationReplay.value
  private def logName = "" //BACK-2.8 settings.YpresentationLog.value

  val log =
    if (replayName != "") new Replayer(new FileReader(replayName))
    else if (logName != "") new Logger(new FileWriter(logName))
    else NullLogger

  import log.logreplay
  debugLog("interactive compiler from 23 Jan")
  debugLog("logger: " + log.getClass + " writing to " + (new java.io.File(logName)).getAbsolutePath)
  debugLog("classpath: "+classPath)  
  /** Print msg only when debugIDE is true. */
  @inline final def debugLog(msg: => String) = 
    if (debugIDE) println(msg)

  /** Inform with msg only when verboseIDE is true. */
  @inline final def informIDE(msg: => String) = 
    if (verboseIDE) reporter.info(NoPosition, msg, true)
  
  //BACK-2.8 (override notthing)
  //override def forInteractive = true
  def forInteractive = true
  override def onlyPresentation = true

  /** A map of all loaded files to the rich compilation units that correspond to them.
   */ 
  val unitOfFile = new LinkedHashMap[AbstractFile, RichCompilationUnit] with
                       SynchronizedMap[AbstractFile, RichCompilationUnit]

  protected val toBeRemoved = new ArrayBuffer[AbstractFile] with SynchronizedBuffer[AbstractFile]

  /** The compilation unit corresponding to a source file
   *  if it does not yet exist create a new one atomically
   *  Note: We want to rmeove this.
   */
  protected[interactive] def getOrCreateUnitOf(source: SourceFile): RichCompilationUnit =
    unitOfFile.synchronized {
      unitOfFile get source.file match {
        case Some(unit) =>
          unit
        case None =>
          println("*** precondition violated: executing operation on non-loaded file " + source)
          val unit = new RichCompilationUnit(source)
          unitOfFile(source.file) = unit
          unit
      }
    }
  
  protected [interactive] def onUnitOf[T](source: SourceFile)(op: RichCompilationUnit => T): T =
    op(unitOfFile.getOrElse(source.file, new RichCompilationUnit(source)))

  /** Work through toBeRemoved list to remove any units.
   *  Then return optionlly unit associated with given source.
   */
  protected[interactive] def getUnit(s: SourceFile): Option[RichCompilationUnit] = {
    toBeRemoved.synchronized {
      for (f <- toBeRemoved) {
        unitOfFile -= f
        allSources = allSources filter (_.file != f)
      }
      toBeRemoved.clear()
    }
    unitOfFile get s.file
  }
  /** A list giving all files to be typechecked in the order they should be checked.
   */
  var allSources: List[SourceFile] = List()

  //BACK-2.8 set currentTyperRun to protected scope to allowing set it in newTyperRun override
  /** The currently active typer run */
  //private var currentTyperRun: TyperRun = _
  protected var currentTyperRun: TyperRun = _
  newTyperRun()

  /** Is a background compiler run needed?
   *  Note: outOfDate is true as long as there is a background compile scheduled or going on.
   */
  protected[interactive] var outOfDate = false

  /** Units compiled by a run with id >= minRunId are considered up-to-date  */
  private[interactive] var minRunId = 1

  private val NoResponse: Response[_] = new Response[Any]

  /** The response that is currently pending, i.e. the compiler
   *  is working on providing an asnwer for it.
   */
  private var pendingResponse: Response[_] = NoResponse 

  // ----------- Overriding hooks in nsc.Global -----------------------
  
  /** Called from typechecker, which signals hereby that a node has been completely typechecked.
   *  If the node includes unit.targetPos, abandons run and returns newly attributed tree.
   *  Otherwise, if there's some higher priority work to be done, also abandons run with a FreshRunReq.
   *  @param  context  The context that typechecked the node
   *  @param  old      The original node
   *  @param  result   The transformed node
   */
  override def signalDone(context: Context, old: Tree, result: Tree) {
    def integrateNew() {
      //Defensive
      if ((context ne null) && (context.unit ne null)) {
        // Don't think this is needed anymore, let's see if we can remove
        context.unit.body = new TreeReplacer(old, result) transform context.unit.body
      }
    }
    if (activeLocks == 0) { // can we try to avoid that condition (?)
      if (context.unit != null && 
          result.pos.isOpaqueRange && 
          (result.pos includes context.unit.targetPos)) {
        integrateNew()
        var located = new TypedLocator(context.unit.targetPos) locateIn result
        if (located == EmptyTree) {
          println("something's wrong: no "+context.unit+" in "+result+result.pos)
          located = result
        }
        throw new TyperResult(located)
      }
      val typerRun = currentTyperRun
      
      while(true) 
        try {
          try {
            pollForWork(old.pos)
      } catch {
            case ex : Throwable =>
          if (context.unit != null) integrateNew()
              log.flush()
              throw ex
      }
          if (typerRun == currentTyperRun)
            return
         
          integrateNew()
          throw FreshRunReq
        } catch {
          case ex : ValidateException => // Ignore, this will have been reported elsewhere
            debugLog("validate exception caught: "+ex)
        }
    }
  }

  /** Called from typechecker every time a context is created.
   *  Registers the context in a context tree
   */
  override def registerContext(c: Context) = c.unit match {
    case u: RichCompilationUnit => addContext(u.contexts, c)
    case _ =>
  }

  /** The top level classes and objects currently seen in the presentation compiler
   */
  private val currentTopLevelSyms = new mutable.LinkedHashSet[Symbol]

  /** The top level classes and objects no longer seen in the presentation compiler
   */
  val deletedTopLevelSyms = new mutable.LinkedHashSet[Symbol] with mutable.SynchronizedSet[Symbol]

  /** Called from typechecker every time a top-level class or object is entered.
   */
  override def registerTopLevelSym(sym: Symbol) { currentTopLevelSyms += sym }
  
//BACK 2.8
// SymbolLoaders.enterToplevelsFromSource doesn't exists (workaround provide by IDE)
//  /** Symbol loaders in the IDE parse all source files loaded from a package for
//   *  top-level idents. Therefore, we can detect top-level symbols that have a name
//   *  different from their source file
//   */
//  override lazy val loaders = new SymbolLoaders {
//    val global: Global.this.type = Global.this
//    override def enterToplevelsFromSource(root: Symbol, name: String, src: AbstractFile) {
//      // todo: change
//      if (root.isEmptyPackageClass) {
//    // currentRun is null for the empty package, since its type is taken during currentRun
//    // initialization. todo: remove when refactored.
//    super.enterToplevelsFromSource(root, name, src)
//      } else {
//    currentRun.compileLate(src)
//      }
//    }
//  }

  // ----------------- Polling ---------------------------------------
  
  case class WorkEvent(atNode: Int, atMillis: Long)

  var moreWorkAtNode: Int = -1
  var nodesSeen = 0

  /** Called from runner thread and signalDone:
   *  Poll for interrupts and execute them immediately.
   *  Then, poll for exceptions and execute them. 
   *  Then, poll for work reload/typedTreeAt/doFirst commands during background checking.
   */
  def pollForWork(pos: Position) {
    def nodeWithWork(): Option[WorkEvent] =
      if (scheduler.moreWork || pendingResponse.isCancelled) Some(new WorkEvent(nodesSeen, System.currentTimeMillis))
      else None

    nodesSeen += 1
    logreplay("atnode", nodeWithWork()) match {
      case Some(WorkEvent(id, _)) => 
        debugLog("some work at node "+id+" current = "+nodesSeen)
//        assert(id >= nodesSeen) 
        moreWorkAtNode = id
      case None =>
    }

    if (nodesSeen >= moreWorkAtNode) {
      
      logreplay("asked", scheduler.pollInterrupt()) match {
        case Some(ir) =>
          try {
            activeLocks += 1
            ir.execute()
          } finally {
            activeLocks -= 1
          }
          pollForWork(pos)
        case _ =>
      }
     
      if (logreplay("cancelled", pendingResponse.isCancelled)) { 
        throw CancelException
      }
    
      logreplay("exception thrown", scheduler.pollThrowable()) match {
        case Some(ex @ FreshRunReq) => 
          newTyperRun()
          minRunId = currentRunId
          if (outOfDate) throw ex
          else outOfDate = true
        case Some(ex: Throwable) => log.flush(); throw ex
        case _ =>
      }
      logreplay("workitem", scheduler.nextWorkItem()) match {
        case Some(action) =>
          try {
            debugLog("picked up work item at "+pos+": "+action)
            action()
            debugLog("done with work item: "+action)
          } finally {
            debugLog("quitting work item: "+action)
          }
        case None =>
      }
    }
  }    
 

  def debugInfo(source : SourceFile, start : Int, length : Int): String = {
    println("DEBUG INFO "+source+"/"+start+"/"+length)
    val end = start+length
    val pos = rangePos(source, start, start, end)

    val tree = locateTree(pos)
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    newTreePrinter(pw).print(tree)
    pw.flush
    
    val typed = new Response[Tree]
    askTypeAt(pos, typed)
    val typ = typed.get.left.toOption match {
      case Some(tree) =>
        val sw = new StringWriter
        val pw = new PrintWriter(sw)
        newTreePrinter(pw).print(tree)
        pw.flush
        sw.toString
      case None => "<None>"      
    }

    val completionResponse = new Response[List[Member]]
    askTypeCompletion(pos, completionResponse)
    val completion = completionResponse.get.left.toOption match {
      case Some(members) =>
        members mkString "\n"
      case None => "<None>"      
    }
    
    source.content.view.drop(start).take(length).mkString+" : "+source.path+" ("+start+", "+end+
    ")\n\nlocateTree:\n"+sw.toString+"\n\naskTypeAt:\n"+typ+"\n\ncompletion:\n"+completion
  }

  // ----------------- The Background Runner Thread -----------------------

  /** The current presentation compiler runner */
  @volatile protected[interactive] var compileRunner = newRunnerThread()

  private var threadId = 0

  /** Create a new presentation compiler runner.
   */
  def newRunnerThread(): Thread = {
    threadId += 1
    compileRunner = new PresentationCompilerThread(this, threadId)
    compileRunner.start()
    compileRunner
  }

  /** Compile all loaded source files in the order given by `allSources`.
   */ 
  protected[interactive] def backgroundCompile() {
    informIDE("Starting new presentation compiler type checking pass")
    //BACK-2.8 reporter.reset has no parenthesis
    reporter.reset
    // remove any files in first that are no longer maintained by presentation compiler (i.e. closed)
    allSources = allSources filter (s => unitOfFile contains (s.file))

    for (s <- allSources; unit <- getUnit(s)) {
      if (!unit.isUpToDate && unit.status != JustParsed) reset(unit) // reparse previously typechecked units.
      if (unit.status == NotLoaded) parse(unit)
    }

    for (s <- allSources; unit <- getUnit(s)) {
      if (!unit.isUpToDate) typeCheck(unit)
    }

    informIDE("Everything is now up to date")
  }

  /** Reset unit to unloaded state */
  def reset(unit: RichCompilationUnit): Unit = {
    unit.depends.clear()
    unit.defined.clear()
    unit.synthetics.clear()
    unit.toCheck.clear()
    unit.targetPos = NoPosition
    unit.contexts.clear()
    unit.body = EmptyTree
    unit.status = NotLoaded
  }

  /** Parse unit and create a name index. */
  def parse(unit: RichCompilationUnit): Unit = {
    debugLog("parsing: "+unit)
    currentTyperRun.compileLate(unit)
    if (debugIDE && !reporter.hasErrors) validatePositions(unit.body)
    if (!unit.isJava) syncTopLevelSyms(unit)
    unit.status = JustParsed
  }

  /** Make sure unit is typechecked
   */
  def typeCheck(unit: RichCompilationUnit) {
    debugLog("type checking: "+unit)
    if (unit.status == NotLoaded) parse(unit)
    unit.status = PartiallyChecked
    currentTyperRun.typeCheck(unit)
    unit.lastBody = unit.body
    unit.status = currentRunId
  }

  /** Update deleted and current top-level symbols sets */
  def syncTopLevelSyms(unit: RichCompilationUnit) {
    val deleted = currentTopLevelSyms filter { sym =>
      /** We sync after namer phase and it resets all the top-level symbols
       *  that survive the new parsing
       *  round to NoPeriod.
       */
      sym.sourceFile == unit.source.file && 
      sym.validTo != NoPeriod && 
      runId(sym.validTo) < currentRunId 
    }
    for (d <- deleted) {
      d.owner.info.decls unlink d
      deletedTopLevelSyms += d
      currentTopLevelSyms -= d
    }
  }
      
  /** Move list of files to front of allSources */
  def moveToFront(fs: List[SourceFile]) {
    allSources = fs ::: (allSources diff fs)
  }

  // ----------------- Implementations of client commands -----------------------
  
  def respond[T](result: Response[T])(op: => T): Unit = 
    respondGradually(result)(Stream(op))

  def respondGradually[T](response: Response[T])(op: => Stream[T]): Unit = {
    val prevResponse = pendingResponse
    try {
      pendingResponse = response
      if (!response.isCancelled) {
        var results = op
        while (!response.isCancelled && results.nonEmpty) {
          val result = results.head
          results = results.tail
          if (results.isEmpty) response set result
          else response setProvisionally result
        }
      }
    } catch {
      case CancelException =>
        debugLog("cancelled")
/* Commented out. Typing should always cancel requests 
      case ex @ FreshRunReq =>
        scheduler.postWorkItem(() => respondGradually(response)(op))
        throw ex
*/
      case ex =>
        if (debugIDE) {
          println("exception thrown during response: "+ex)
          ex.printStackTrace()
        }
        response raise ex
    } finally {
      pendingResponse = prevResponse
    }
  }

  def reloadSource(source: SourceFile) {
    val unit = new RichCompilationUnit(source)
    unitOfFile(source.file) = unit
    reset(unit)
    parse(unit)
  }
  /** Make sure a set of compilation units is loaded and parsed */
  def reloadSources(sources: List[SourceFile]) {
    newTyperRun()
    minRunId = currentRunId
    sources foreach reloadSource
    moveToFront(sources)
  }

  /** Make sure a set of compilation units is loaded and parsed */
  def reload(sources: List[SourceFile], response: Response[Unit]) {
    informIDE("reload" + sources)
    respond(response)(reloadSources(sources))
    if (outOfDate)
      if (activeLocks == 0) throw FreshRunReq // cancel background compile
      else scheduler.raise(FreshRunReq)  // cancel background compile on the next poll
    else outOfDate = true            // proceed normally and enable new background compile
  }

  /** A fully attributed tree located at position `pos`  */
  def typedTreeAt(pos: Position): Tree = {
    informIDE("typedTreeAt " + pos)
    val tree = locateTree(pos)
    debugLog("at pos "+pos+" was found: "+tree+tree.pos.show)
    if (stabilizedType(tree) ne null) {
      debugLog("already attributed")
      tree
    } else {
      val unit = getOrCreateUnitOf(pos.source)
      unit.targetPos = pos
      try {
        debugLog("starting targeted type check")
        //newTyperRun()   // not needed for idempotent type checker phase
        typeCheck(unit)
        println("tree not found at "+pos)
        EmptyTree
      } catch {
        case ex: TyperResult => new Locator(pos) locateIn ex.tree
      } finally {
        unit.targetPos = NoPosition
      }
    }
  }

  /** A fully attributed tree corresponding to the entire compilation unit  */
  def typedTree(source: SourceFile, forceReload: Boolean): Tree = {
    informIDE("typedTree" + source + " forceReload: " + forceReload)
    val unit = getOrCreateUnitOf(source)
    if (forceReload) reset(unit)
    if (unit.status <= PartiallyChecked) {
      //newTyperRun()   // not deeded for idempotent type checker phase
      typeCheck(unit)
    }
    unit.body
  }

  /** Set sync var `response` to a fully attributed tree located at position `pos`  */
  def getTypedTreeAt(pos: Position, response: Response[Tree]) {
    respond(response)(typedTreeAt(pos))
  }

  /** Set sync var `response` to a fully attributed tree corresponding to the
   *  entire compilation unit  */
  def getTypedTree(source : SourceFile, forceReload: Boolean, response: Response[Tree]) {
    respond(response)(typedTree(source, forceReload))
  }

  /** Set sync var `response` to the last fully attributed tree produced from the
   *  entire compilation unit  */
  def getLastTypedTree(source : SourceFile, response: Response[Tree]) {
    informIDE("getLastTyped" + source)
    respond(response) {
      val unit = getOrCreateUnitOf(source)
      if (unit.status > PartiallyChecked) unit.body
      else if (unit.lastBody ne EmptyTree) unit.lastBody
      else typedTree(source, false)
    }
  }

  def getLinkPos(sym: Symbol, source: SourceFile, response: Response[Position]) {
    informIDE("getLinkPos "+sym+" "+source)
    respond(response) {
      val preExisting = unitOfFile isDefinedAt source.file
      reloadSources(List(source))
      val owner = sym.owner
      if (owner.isClass) {
        val pre = adaptToNewRunMap(ThisType(owner))
        val newsym = pre.decl(sym.name) filter { alt =>
          sym.isType || {
            try {
              val tp1 = pre.memberType(alt) onTypeError NoType
              val tp2 = adaptToNewRunMap(sym.tpe)
              matchesType(tp1, tp2, false)
            } catch {
              case ex: Throwable =>
                println("error in hyperlinking: "+ex)
                ex.printStackTrace()
                false
            }
          }
        }
        if (!preExisting) removeUnitOf(source)
        if (newsym == NoSymbol) {
          debugLog("link not found "+sym+" "+source+" "+pre)
          NoPosition
        } else if (newsym.isOverloaded) {
          debugLog("link ambiguous "+sym+" "+source+" "+pre+" "+newsym.alternatives)
          NoPosition
        } else {
          debugLog("link found for "+newsym+": "+newsym.pos)
          newsym.pos
        }
      } else 
        debugLog("link not in class "+sym+" "+source+" "+owner)
        NoPosition
    }
  }

  def stabilizedType(tree: Tree): Type = tree match {
    case Ident(_) if tree.symbol.isStable => 
      singleType(NoPrefix, tree.symbol)
    case Select(qual, _) if qual.tpe != null && tree.symbol.isStable => 
      singleType(qual.tpe, tree.symbol)
    case Import(expr, selectors) =>
      tree.symbol.info match {
        case analyzer.ImportType(expr) => expr match {
          case s@Select(qual, name) => singleType(qual.tpe, s.symbol)
          case i : Ident => i.tpe
          case _ => tree.tpe
        }
        case _ => tree.tpe
      }
    
    case _ => tree.tpe
  }

  import analyzer.{SearchResult, ImplicitSearch}

  def getScopeCompletion(pos: Position, response: Response[List[Member]]) {
    informIDE("getScopeCompletion" + pos)
    respond(response) { scopeMembers(pos) }
  }

  val Dollar = newTermName("$")

  /** Return all members visible without prefix in context enclosing `pos`. */
  def scopeMembers(pos: Position): List[ScopeMember] = {
    typedTreeAt(pos) // to make sure context is entered
    val context = doLocateContext(pos)
    val locals = new LinkedHashMap[Name, ScopeMember]
    def addScopeMember(sym: Symbol, pre: Type, viaImport: Tree) =
      if (!sym.name.decode.containsName(Dollar) &&  
          !sym.isSynthetic &&
          sym.hasRawInfo &&
          !locals.contains(sym.name)) {
        locals(sym.name) = new ScopeMember(
          sym, 
          pre.memberType(sym) onTypeError ErrorType, 
          context.isAccessible(sym, pre, false),
          viaImport)
      }
    var cx = context
    while (cx != NoContext) {
      for (sym <- cx.scope)
        addScopeMember(sym, NoPrefix, EmptyTree)
      if (cx == cx.enclClass) {
        val pre = cx.prefix
        for (sym <- pre.members) 
          addScopeMember(sym, pre, EmptyTree)
      }
      cx = cx.outer
    }

    for (imp <- context.imports) {
      val pre = imp.qual.tpe
      for (sym <- imp.allImportedSymbols) {
        addScopeMember(sym, pre, imp.qual)
      }
    }
    val result = locals.values.toList
//    if (debugIDE) for (m <- result) println(m)
    result
  }

  def getTypeCompletion(pos: Position, response: Response[List[Member]]) {
    informIDE("getTypeCompletion " + pos)
    respondGradually(response) { typeMembers(pos) }
    //if (debugIDE) typeMembers(pos)
  }

  def typeMembers(pos: Position): Stream[List[TypeMember]] = {
    var tree = typedTreeAt(pos)

    // if tree consists of just x. or x.fo where fo is not yet a full member name
    // ignore the selection and look in just x.
    tree match {
      case Select(qual, name) if tree.tpe == ErrorType => tree = qual
      case _ => 
    }

    val context = doLocateContext(pos)

    if (tree.tpe == null)
      // TODO: guard with try/catch to deal with ill-typed qualifiers.
      tree = analyzer.newTyper(context).typedQualifier(tree)
      
    debugLog("typeMembers at "+tree+" "+tree.tpe)

    val superAccess = tree.isInstanceOf[Super]
    val scope = new Scope
    val members = new LinkedHashMap[Symbol, TypeMember]

    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) {
      val symtpe = pre.memberType(sym) onTypeError ErrorType
      if (scope.lookupAll(sym.name) forall (sym => !(members(sym).tpe matches symtpe))) {
        scope enter sym
        members(sym) = new TypeMember(
          sym,
          symtpe,
          context.isAccessible(sym, pre, superAccess && (viaView == NoSymbol)),
          inherited,
          viaView)
      }
    }        

    /** Create a function application of a given view function to `tree` and typechecked it.
     */
    def viewApply(view: SearchResult): Tree = {
      assert(view.tree != EmptyTree)
      try {
        analyzer.newTyper(context.makeImplicit(reportAmbiguousErrors = false))
          .typed(Apply(view.tree, List(tree)) setPos tree.pos)
      } catch {
        case ex: TypeError => 
          debugLog("type error caught")
          EmptyTree
      }
    }
    
    /** Names containing $ are not valid completions. */
    def shouldDisplay(sym: Symbol): Boolean = 
      !sym.name.toString.contains("$")

    val pre = stabilizedType(tree)
    val ownerTpe = tree.tpe match {
      case analyzer.ImportType(expr) => expr.tpe
      case null => pre
      case _ => tree.tpe
    }

    for (sym <- ownerTpe.decls if shouldDisplay(sym))
      addTypeMember(sym, pre, false, NoSymbol)
    members.values.toList #:: {
      for (sym <- ownerTpe.members if shouldDisplay(sym))
        addTypeMember(sym, pre, true, NoSymbol)
      members.values.toList #:: {
        val applicableViews: List[SearchResult] = 
          new ImplicitSearch(tree, functionType(List(ownerTpe), AnyClass.tpe), isView = true, context.makeImplicit(reportAmbiguousErrors = false))
            .allImplicits
        for (view <- applicableViews) {
          val vtree = viewApply(view)
          val vpre = stabilizedType(vtree)
          for (sym <- vtree.tpe.members) {
            addTypeMember(sym, vpre, false, view.tree.symbol)
          }
        }
        Stream(members.values.toList)
      }
    }
  }

  // ---------------- Helper classes ---------------------------

  /** A transformer that replaces tree `from` with tree `to` in a given tree */
  class TreeReplacer(from: Tree, to: Tree) extends Transformer {
    override def transform(t: Tree): Tree = {
      if (t == from) to
      else if ((t.pos includes from.pos) || t.pos.isTransparent) super.transform(t)
      else t
    }
  }

  /** The typer run */
  class TyperRun extends Run {
    // units is always empty

    /** canRedefine is used to detect double declarations of classes and objects
     *  in multiple source files.
     *  Since the IDE rechecks units several times in the same run, these tests
     *  are disabled by always returning true here.
     */
    override def canRedefine(sym: Symbol) = true

    def typeCheck(unit: CompilationUnit): Unit = {
      activeLocks = 0
      applyPhase(typerPhase, unit)
    } 

    /** Apply a phase to a compilation unit
     *  @return true iff typechecked correctly
     */
    private def applyPhase(phase: Phase, unit: CompilationUnit) {
      val oldSource = reporter.getSource          
      reporter.withSource(unit.source) {
        atPhase(phase) { phase.asInstanceOf[GlobalPhase] applyPhase unit }
      }
    }
  }
  
  def newTyperRun() {
    currentTyperRun = new TyperRun
  }

  class TyperResult(val tree: Tree) extends ControlThrowable
  
  assert(globalPhase.id == 0)
  
  implicit def addOnTypeError[T](x: => T): OnTypeError[T] = new OnTypeError(x)
  
  class OnTypeError[T](op: => T) {
    def onTypeError(alt: => T) = try {
      op
    } catch {
      case ex: TypeError => alt
    }
  }
}

object CancelException extends Exception
