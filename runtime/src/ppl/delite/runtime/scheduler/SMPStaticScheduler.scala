package ppl.delite.runtime.scheduler

import ppl.delite.runtime.Config
import ppl.delite.runtime.codegen.{ExecutableGenerator, DeliteExecutable}
import ppl.delite.runtime.codegen.kernels.scala.{Reduce_SMP_Array_Generator, Map_SMP_Array_Generator}
import ppl.delite.runtime.graph.ops.{OP_Reduce, OP_Map, DeliteOP}
import ppl.delite.runtime.graph.DeliteTaskGraph
import java.util.ArrayDeque

/**
 * Author: Kevin J. Brown
 * Date: Oct 11, 2010
 * Time: 1:02:57 AM
 * 
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

/**
 * A completely static scheduler for an SMP system
 *
 * @author Kevin J. Brown
 *
 */

final class SMPStaticScheduler extends StaticScheduler {

  private val numThreads = Config.numThreads

  private val procs = new Array[ArrayDeque[DeliteOP]](numThreads)
  for (i <- 0 until numThreads) procs(i) = new ArrayDeque[DeliteOP]

  private val opQueue = new ArrayDeque[DeliteOP]

  def schedule(graph: DeliteTaskGraph) : PartialSchedule = {
    //traverse nesting & schedule sub-graphs
    //TODO: implement functionality for nested graphs
    scheduleFlat(graph)

    //return schedule
    createPartialSchedule
  }

  private def scheduleFlat(graph: DeliteTaskGraph) {
    enqueueRoots(graph)
    while (!opQueue.isEmpty) {
      val op = opQueue.remove
      scheduleOne(op)
      processConsumers(op)
    }
  }

  //NOTE: this is currently the simple scheduler from Delite 1.0
  var nextThread = 0

  private def scheduleOne(op: DeliteOP) {
    if (op.isDataParallel) {
      split(op) //split and schedule op across all threads
    }
    else {
      //look for best place to put this op (simple nearest-neighbor clustering)
      var i = 0
      var notDone = true
      val deps = op.getDependencies
      while (i < numThreads && notDone) {
        if (deps.contains(procs(i).peekLast)) {
          procs(i).add(op)
          op.scheduledResource = i
          notDone = false
          if (nextThread == i) nextThread = (nextThread + 1) % numThreads
        }
        i += 1
      }
      //else submit op to next thread in the rotation (round-robin)
      if (notDone) {
        procs(nextThread).add(op)
        op.scheduledResource = nextThread
        nextThread = (nextThread + 1) % numThreads
      }
      op.isScheduled = true
    }
  }

  private def enqueueRoots(graph: DeliteTaskGraph) {
    //val end = graph.result
    //traverse(end)
    for (op <- graph.ops) {
      op.processSchedulable
      if (op.isSchedulable) opQueue.add(op)
    }
  }

  private def traverse(op: DeliteOP) {
    if (!op.isSchedulable) { //not already in opQueue
      op.processSchedulable
      if (op.isSchedulable) opQueue.add(op)
    }
    for (dep <- op.getDependencies) {
      traverse(dep)
    }
  }

  private def processConsumers(op: DeliteOP) {
    for (c <- op.getConsumers) {
      if (!c.isSchedulable) {//if not already in opQueue (protects against same consumer appearing in list multiple times)
        c.processSchedulable
        if (c.isSchedulable) opQueue.add(c)
      }
    }
  }

  private def split(op: DeliteOP) {
    op match { //NOTE: match on OP type since different data parallel ops can have different semantics / scheduling implications
      case map: OP_Map => {
        for (i <- 0 until numThreads) {
          val chunk = Map_SMP_Array_Generator.makeChunk(map, i, numThreads)
          procs(i).add(chunk)
          chunk.isScheduled = true
          chunk.scheduledResource = i
        }
      }
      case reduce: OP_Reduce => {
        for (i <- 0 until numThreads) {
          val chunk = Reduce_SMP_Array_Generator.makeChunk(reduce, i, numThreads)
          procs(i).add(chunk)
          chunk.isScheduled = true
          chunk.scheduledResource = i
        }
      }
      case other => error("OP type not recognized: " + other.getClass.getSimpleName)
    }
  }

  private def createPartialSchedule = {
    new PartialSchedule(procs)
  }

}
