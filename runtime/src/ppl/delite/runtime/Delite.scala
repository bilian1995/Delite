package ppl.delite.runtime

import codegen._
import executor._
import graph.ops.{EOP_Global, Arguments}
import graph.targets.Targets
import graph.{TestGraph, DeliteTaskGraph}
import profiler.Profiling
import scheduler._
import tools.nsc.io._

/**
 * Author: Kevin J. Brown
 * Date: Oct 11, 2010
 * Time: 5:02:38 PM
 *
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

object Delite {

  private var _executor: Executor = _
  def executor = _executor

  private var mainThread: Thread = _
  private var outstandingException: Throwable = _

  //TODO: Remove this. This is only used for cluster version GPU runtime code generation.
  var inputArgs: Array[String] = _

  var expectedResources: Seq[Int] = Seq() //TODO: better way to pass this through?

  private def printArgs(args: Array[String]) {
    if(args.length == 0) {
      println("Not enough arguments.\nUsage: [Launch Runtime Command] filename.deg arguments*")
      exit(-1)
    }
    println("Delite Runtime executing with the following arguments:")
    println(args.mkString(","))
  }

  private def printConfig() {
    println("Delite Runtime executing with: " + Config.numThreads + " Scala thread(s), " + Config.numCpp + " Cpp thread(s), " + Config.numCuda + " Cuda(s), " + Config.numOpenCL + " OpenCL(s)")
  }

  def main(args: Array[String]) {
    if (Config.clusterMode == 1) //master (scheduler)
      DeliteMesosScheduler.main(args)
    else if (Config.clusterMode == 2) //slave (executor)
      DeliteMesosExecutor.main(args)
    else
      embeddedMain(args, Map())
  }

  def embeddedMain(args: Array[String], staticData: Map[String,_]) {
    inputArgs = args
    printArgs(args)
    printConfig()

    walkAndRun(args(0), List(args.drop(1)), staticData)
  }

  def execute(degFile: String, args:Any*) = {
    walkAndRun(degFile, args, Map())
  }

  def executeCached(classPrefix: String, args: Any*) = {
    walkAndRun(classPrefix, args, Map())
  }

  def walkAndRun(appName: String, args: Seq[Any], staticData: Map[String,_]) = {
    mainThread = Thread.currentThread

    //extract application arguments
    Arguments.args = args
    Arguments.staticDataMap = staticData
    var appResult: Any = null

    _executor = Config.executor match {
      case "default" => new MultiAccExecutor
      case "acc" => new MultiAccExecutor
      case e => throw new IllegalArgumentException("Requested executor is not recognized ("+e+")")
    }

    def abnormalShutdown() {
      if (executor != null) executor.shutdown()
      if (!Config.alwaysKeepCache)
        Directory(Path(Config.codeCacheHome)).deleteRecursively() //clear the code cache (could be corrupted)
    }

    def walkTime() = {
      //load task graph
      val graph = loadDeliteDEG(appName)
      //val graph = new TestGraph
    
      //Print warning if there is no op that supports the target
      if(Config.numCpp>0 && !graph.targets(Targets.Cpp)) { Config.numCpp = 0; println("[WARNING] No Cpp target op is generated!") }
      if(Config.numCuda>0 && !graph.targets(Targets.Cuda)) { Config.numCuda = 0; println("[WARNING] No Cuda target op is generated!") }
      if(Config.numOpenCL>0 && !graph.targets(Targets.OpenCL)) { Config.numOpenCL = 0; println("[WARNING] No OpenCL target op is generated!") }

      val scheduler = Config.scheduler match {
        case "static" => new AccStaticScheduler(Config.numThreads, Config.numCpp, Config.numCuda, Config.numOpenCL)
        case "dynamic" => new AccStaticScheduler(if (Config.numThreads > 0) 1 else 0, if (Config.numCpp > 0) 1 else 0, Config.numCuda, Config.numOpenCL)
        case e => throw new IllegalArgumentException("Requested scheduler is not recognized ("+e+")")
      }

      Config.deliteBuildHome = graph.kernelPath

      //load kernels & data structures
      loadSources(graph)

      //initialize profiler
      Profiling.init(graph)
      
      //schedule
      scheduler.schedule(graph)

      //compile
      Compilers.compileSchedule(graph)
    }

    def runTime(executable: StaticSchedule) {
      //execute
      if (Config.clusterMode == 2) { //slave executor
        DeliteMesosExecutor.awaitWork()
      }
      else { //master executor (including single-node execution)
        for (i <- 1 to Config.numRuns) {
          if (Config.performWalk) println("Beginning Execution Run " + i)
          Profiling.startRun()
          EOP_Global.setbarrier(executable.resources.count(r => !r.isEmpty)) // set the barrier count
          executor.run(executable)
          EOP_Global.await() //await the end of the application program
          appResult = EOP_Global.take() //get the result of the application
          Profiling.endRun()           
          System.gc()
        }
      }      
    }

    try {
      executor.init() //call this first because could take a while and can be done in parallel
      val executable = if (Config.performWalk) walkTime() else findExecutables(appName)
      if (Config.performRun) runTime(executable)  
      executor.shutdown()
    }
    catch {
      case i: InterruptedException => abnormalShutdown(); throw outstandingException //a worker thread threw the original exception        
      case e: Throwable => abnormalShutdown(); throw e
    }
    finally {
      Arguments.args = Nil
      Arguments.staticDataMap = null
    }

    appResult
  }

  def loadDeliteDEG(filename: String) = {
    val deg = Path(filename)
    if (!deg.isFile)
      error(filename + " does not exist")
    DeliteTaskGraph(deg.jfile)
  }

  def loadSources(graph: DeliteTaskGraph) {
    CodeCache.verifyCache()
    for (target <- Targets.values) {
      if (graph.targets contains target) {
        val dir = Directory(Path(graph.kernelPath + File.separator + Compilers(target).target + File.separator).toAbsolute)
        if (!dir.exists) throw new java.io.FileNotFoundException("generated " + target + " directory does not exist")
        Compilers(target).cacheDegSources(dir)
      }
    }
  }

  def findExecutables(appName: String): StaticSchedule = {
    val numResources = Config.numThreads + Config.numCpp + Config.numCuda + Config.numOpenCL
    Compilers.createSchedule(this.getClass.getClassLoader, appName, numResources, expectedResources)
  }

  //abnormal shutdown
  def shutdown(reason: Throwable) {
    outstandingException = reason
    mainThread.interrupt()
  }

}
