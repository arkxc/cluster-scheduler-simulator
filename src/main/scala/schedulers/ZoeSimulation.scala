/**
  * Copyright (c) 2016 Eurecom
  * All rights reserved.
  **
  *Redistribution and use in source and binary forms, with or without
  *modification, are permitted provided that the following conditions are met:
  **
  *Redistributions of source code must retain the above copyright notice, this
  *list of conditions and the following disclaimer. Redistributions in binary
  *form must reproduce the above copyright notice, this list of conditions and the
  *following disclaimer in the documentation and/or other materials provided with
  *the distribution.
  **
  *Neither the name of Eurecom nor the names of its contributors may be used to
*endorse or promote products derived from this software without specific prior
*written permission.
  **
 *THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
*ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
*WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
*DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
*FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
*DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
*SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
*CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
*OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
*OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  */

package ClusterSchedulingSimulation.schedulers

import ClusterSchedulingSimulation.{CellState, Scheduler, _}
import org.apache.log4j.Logger

import scala.collection.{AbstractIterator, Iterator, mutable}

/* This class and its subclasses are used by factory method
 * ClusterSimulator.newScheduler() to determine which type of Simulator
 * to create and also to carry any extra fields that the factory needs to
 * construct the simulator.
 */
class ZoeSimulatorDesc(schedulerDescs: Seq[SchedulerDesc],
                       runTime: Double,
                       allocationMode: AllocationModes.Value)
  extends ClusterSimulatorDesc(runTime, allocationMode){
  override
  def newSimulator(constantThinkTime: Double,
                   perTaskThinkTime: Double,
                   blackListPercent: Double,
                   schedulerWorkloadsToSweepOver: Map[String, Seq[String]],
                   workloadToSchedulerMap: Map[String, Seq[String]],
                   cellStateDesc: CellStateDesc,
                   workloads: Seq[Workload],
                   prefillWorkloads: Seq[Workload],
                   logging: Boolean = false): ClusterSimulator = {
    val schedulers = mutable.HashMap[String, Scheduler]()
    // Create schedulers according to experiment parameters.
    schedulerDescs.foreach(schedDesc => {
      // If any of the scheduler-workload pairs we're sweeping over
      // are for this scheduler, then apply them before
      // registering it.
      val constantThinkTimes = mutable.HashMap[String, Double](
        schedDesc.constantThinkTimes.toSeq: _*)
      val perTaskThinkTimes = mutable.HashMap[String, Double](
        schedDesc.perTaskThinkTimes.toSeq: _*)
      var newBlackListPercent = 0.0
      if (schedulerWorkloadsToSweepOver
        .contains(schedDesc.name)) {
        newBlackListPercent = blackListPercent
        schedulerWorkloadsToSweepOver(schedDesc.name)
          .foreach(workloadName => {
            constantThinkTimes(workloadName) = constantThinkTime
            perTaskThinkTimes(workloadName) = perTaskThinkTime
          })
      }
      schedulers(schedDesc.name) =
        new ZoeScheduler(schedDesc.name,
          constantThinkTimes.toMap,
          perTaskThinkTimes.toMap,
          math.floor(newBlackListPercent *
            cellStateDesc.numMachines.toDouble).toInt)
    })

    val cellState = new CellState(cellStateDesc.numMachines,
      cellStateDesc.cpusPerMachine,
      cellStateDesc.memPerMachine,
      conflictMode = "resource-fit",
      transactionMode = "all-or-nothing")

    new ClusterSimulator(cellState,
      schedulers.toMap,
      workloadToSchedulerMap,
      workloads,
      prefillWorkloads,
      allocationMode,
      logging)
  }
}

class ZoeScheduler(name: String,
                   constantThinkTimes: Map[String, Double],
                   perTaskThinkTimes: Map[String, Double],
                   numMachinesToBlackList: Double = 0)
  extends Scheduler(name,
    constantThinkTimes,
    perTaskThinkTimes,
    numMachinesToBlackList) {
  val logger = Logger.getLogger(this.getClass.getName)
  logger.debug("scheduler-id-info: %d, %s, %d, %s, %s"
    .format(Thread.currentThread().getId,
      name,
      hashCode(),
      constantThinkTimes.mkString(";"),
      perTaskThinkTimes.mkString(";")))

  var pendingQueueAsList = new scala.collection.mutable.ListBuffer[Job]()
  override def jobQueueSize = pendingQueueAsList.count(_ != null)

  var moldableQueueIterator = pendingQueueAsList.iterator
  var numJobsInQueue: Int = 0

  var moldableQueueLength: Int = 0

  var elasticPendingQueue = new collection.mutable.ListBuffer[Job]()
  var elasticQueueIterator = elasticPendingQueue.iterator
  var numElasticJobsInQueue: Int = 0
  var schedulingElastic: Boolean = false
  var interruptElasticScheduling: Boolean = false

  var privateCellState: CellState = null

  val schedulerPrefix = "[%s]".format(name)

  /**
    * This function gives the next job in the queue
    *
    * @param iterator the iterator of the queue
    * @return  A tuple with:
    *          the next job in the queue or null if the end of the queue has been reached
    *          a counter with the number of elements skipped
    */
  def getNextJobInQueue(iterator: Iterator[Job]): (Job, Long) ={
    var elementsSkipped: Long = 0
    while(iterator.hasNext){
      val result = iterator.next()
      if(result != null)
        return (result, elementsSkipped)
      elementsSkipped += 1
    }
    (null, elementsSkipped)
  }

  def removeMoldableJob(job: Job): Unit = {
    val idx = pendingQueueAsList.indexOf(job)
    if(idx != -1 && pendingQueueAsList(idx) != null){
      numJobsInQueue -= 1
      pendingQueueAsList(idx) = null
    }

//    pendingQueueAsList -= job
//    moldableQueueIterator = pendingQueueAsList.iterator
//    for (i <- 0 until currentIndex)
//        moldableQueueIterator.next()
  }


  def removeElasticJob(job: Job): Unit = {
    val idx = elasticPendingQueue.indexOf(job)
    if(idx != -1 && elasticPendingQueue(idx) != null){
      numElasticJobsInQueue -= 1
      elasticPendingQueue(idx) = null
    }
//    elasticPendingQueue -= job
//    elasticQueueIterator = elasticPendingQueue.iterator
//    for (i <- 0 until currentIndex)
//      elasticQueueIterator.next()
  }

  def addElasticJob(job: Job): Unit = {
    numElasticJobsInQueue += 1
    elasticPendingQueue += job
  }


  def isAllocationSuccessfully(claimDeltas: Seq[ClaimDelta], job: Job): Boolean = {
    if(simulator.allocationMode == AllocationModes.Incremental)
      claimDeltas.nonEmpty
    else if(simulator.allocationMode == AllocationModes.All)
      claimDeltas.size == job.moldableTasks
    else
      false
  }

  def syncCellState() {
    privateCellState = simulator.cellState.copy
    simulator.logger.debug(schedulerPrefix + " Scheduler %s (%d) has new private cell state %d"
      .format(name, hashCode, privateCellState.hashCode))
  }

  override
  def addJob(job: Job) = {
    simulator.logger.info(schedulerPrefix + " Enqueued job %d of workload type %s."
      .format(job.id, job.workloadName))
    super.addJob(job)

    pendingQueueAsList += job
    numJobsInQueue += 1
    scheduleNextJob()
  }


  /**
    * This function is called when we are at the end of the moldable queue or when no job is
    * inside that queue.
    * Now we can try to speed up them by adding some elastic tasks.
    */
  def scheduleElasticJobs(): Unit = {
    scheduling = false
    moldableQueueIterator = pendingQueueAsList.iterator

    moldableQueueLength = pendingQueueAsList.size
    elasticQueueIterator = elasticPendingQueue.iterator
    interruptElasticScheduling = false
    scheduleNextElasticJob()
  }

  /**
    * Checks to see if there is currently a job in this scheduler's job queue.
    * If there is, and this scheduler is not currently scheduling a job, then
    * pop that job off of the queue and "begin scheduling it". Scheduling a
    * job consists of setting this scheduler's state to scheduling = true, and
    * adding a finishSchedulingJobAction to the simulators event queue by
    * calling afterDelay().
    */
  def scheduleNextJob(): Unit = {
    if (!scheduling){
      if (numJobsInQueue > 0) {
        scheduling = true
        val (job, elementsSkipped) = getNextJobInQueue(moldableQueueIterator)
        if (job == null){
          if(elementsSkipped == pendingQueueAsList.length)
            simulator.logger.error(schedulerPrefix + ("getNextJobInQueue has done a full check on the entire queue, " +
              "but no job was found and numJobsInQueue = %d.").format(numJobsInQueue))
          simulator.logger.info(schedulerPrefix + " Reached the end of the queue. Let's try to schedule some Elastic Services.")
          scheduleElasticJobs()
          return
        }

        syncCellState()
        val jobThinkTime = getThinkTime(job)
        val jobPrefix = "[Job %d (%s)] ".format(job.id, job.workloadName)
        simulator.logger.info(schedulerPrefix + jobPrefix + "Started %f seconds of scheduling thinktime."
          .format(jobThinkTime))
        simulator.afterDelay(jobThinkTime) {
          var unscheduledTasks: Int = job.unscheduledTasks

          simulator.logger.info((schedulerPrefix + jobPrefix + "Finished %f seconds of scheduling " +
            "thinktime; checking resources for %d tasks with %f cpus and %f mem each.")
            .format(jobThinkTime,
              unscheduledTasks,
              job.cpusPerTask,
              job.memPerTask))
          job.numSchedulingAttempts += 1
          job.numTaskSchedulingAttempts += unscheduledTasks

          assert(unscheduledTasks > 0)
          val claimDeltas = scheduleJob(job, privateCellState)
          if (isAllocationSuccessfully(claimDeltas, job)) {
            val commitResult = simulator.cellState.commit(claimDeltas)
            if (commitResult.committedDeltas.nonEmpty) {
              recordUsefulTimeScheduling(job, jobThinkTime, job.numSchedulingAttempts == 1)

              unscheduledTasks -= commitResult.committedDeltas.size
              job.claimDeltas ++= commitResult.committedDeltas
              job.unscheduledTasks = unscheduledTasks
              numSuccessfulTransactions += 1
              job.finalStatus = JobStates.Partially_Scheduled
              if (job.firstScheduled) {
                job.timeInQueueTillFirstScheduled = simulator.currentTime - job.lastEnqueued
                job.firstScheduled = false
              }

              simulator.logger.info(schedulerPrefix + jobPrefix + "Scheduled %d tasks, %d remaining."
                .format(claimDeltas.size, job.unscheduledTasks))
            } else {
              numFailedTransactions += 1
              simulator.logger.info(schedulerPrefix + jobPrefix + "There was a conflict when committing the task allocation to the real cell.")
              recordWastedTimeScheduling(job, jobThinkTime, job.numSchedulingAttempts == 1)
            }

          } else {
            recordWastedTimeScheduling(job, jobThinkTime, job.numSchedulingAttempts == 1)
            numNoResourcesFoundSchedulingAttempts += 1

            simulator.logger.info((schedulerPrefix + jobPrefix + "No tasks scheduled (%f cpu %f mem per task) " +
              "during this scheduling attempt, recording " +
              "wasted time. %d unscheduled tasks remaining.")
              .format(job.cpusPerTask,
                job.memPerTask,
                unscheduledTasks))
          }

          // If the job isn't yet fully scheduled, put it back in the queue.
          if (unscheduledTasks > 0) {
            simulator.logger.info((schedulerPrefix + jobPrefix + " Not fully scheduled, %d / %d tasks remain " +
              "(shape: %f cpus, %f mem per task). Leaving it " +
              "in the queue.").format(unscheduledTasks,
              job.moldableTasks,
              job.cpusPerTask,
              job.memPerTask))

            if (giveUpSchedulingJob(job)) {
              numJobsTimedOutScheduling += 1
              job.finalStatus = JobStates.TimedOut
              removeMoldableJob(job)
              pendingQueueAsList -= job
              simulator.logger.info((schedulerPrefix + jobPrefix + "Abandoning (%f cpu %f mem per task) with %d/%d " +
                "remaining tasks, after %d scheduling " +
                "attempts.").format(job.cpusPerTask,
                job.memPerTask,
                unscheduledTasks,
                job.moldableTasks,
                job.numSchedulingAttempts))
            }
          } else {
            val jobDuration: Double = job.jobDuration
            job.jobStartedWorking = simulator.currentTime
            job.jobFinishedWorking = simulator.currentTime + jobDuration

            simulator.logger.info(schedulerPrefix + jobPrefix + "Adding finished event after %f seconds to wake up scheduler.".format(jobDuration))
            simulator.cellState.scheduleEndEvents(job.claimDeltas, delay = jobDuration, jobId = job.id)
            simulator.afterDelay(jobDuration, eventType = EventTypes.Trigger, itemId = job.id) {
              simulator.logger.info(schedulerPrefix + jobPrefix + "Completed.")
              moldableQueueIterator = pendingQueueAsList.iterator
              // Remove the Job from the elastic queue, if it was present.
              removeElasticJob(job)
              interruptElasticScheduling = true
              scheduleNextJob()
            }

            // All tasks in job scheduled so don't put it back in pendingQueueAsList.
            job.finalStatus = JobStates.Fully_Scheduled
            job.timeInQueueTillFullyScheduled = simulator.currentTime - job.lastEnqueued
            removeMoldableJob(job)
            if(job.elasticTasksUnscheduled > 0)
              addElasticJob(job)

            simulator.logger.info((schedulerPrefix + jobPrefix + "Fully-Scheduled (%f cpu %f mem per task), " +
              "after %d scheduling attempts.").format(job.cpusPerTask,
              job.memPerTask,
              job.numSchedulingAttempts))
          }
          scheduling = false
          if (!moldableQueueIterator.hasNext) {
            simulator.logger.info(schedulerPrefix + " Reached the end of the queue. Let's try to schedule some Elastic Services.")
            scheduleElasticJobs()
            // By not calling scheduleNextJob() when we reach the end of the queue
            // we reduce the number of time that the scheduler tries to schedule a job.
            // If the job schedule failed and there have been no changes in the occupied resource..
            // why keep trying (in the simulation)?
          } else {
            scheduleNextJob()
          }
        }
      }else{
        simulator.logger.info(schedulerPrefix + " No more jobs in the queue. Let's try to schedule some Elastic Services.")
        scheduleElasticJobs()
      }
    }
  }

  /**
    * Checks to see if there is currently a job in this scheduler's job queue.
    * If there is, and this scheduler is not currently scheduling a job, then
    * pop that job off of the queue and "begin scheduling it". Scheduling a
    * job consists of setting this scheduler's state to scheduling = true, and
    * adding a finishSchedulingJobAction to the simulators event queue by
    * calling afterDelay().
    */
  def scheduleNextElasticJob(): Unit = {
    if (!scheduling && !schedulingElastic && numElasticJobsInQueue > 0) {
      val elasticPrefix = "[Elastic]"
      schedulingElastic = true
      val (job, elementsSkipped) = getNextJobInQueue(elasticQueueIterator)
      if(job == null && !elasticQueueIterator.hasNext){
        if(elementsSkipped == elasticPendingQueue.length)
          simulator.logger.error(schedulerPrefix + elasticPrefix + (" getNextJobInQueue has done a full check on the entire queue, " +
            "but no job was found and numJobsInQueue = %d.").format(numJobsInQueue))
        schedulingElastic = false
        elasticQueueIterator = elasticPendingQueue.iterator
        // By adding this return we reduce the number of time that the scheduler tries to schedule a job.
        // If the job schedule failed and there have been no changes in the occupied resource..
        // why keep trying (in the simulation)?
        return
      }

      syncCellState()
      val jobThinkTime = getThinkTime(job, job.elasticTasksUnscheduled)
      val jobPrefix = "[Job %d (%s)] ".format(job.id, job.workloadName)
      simulator.logger.info(schedulerPrefix + elasticPrefix + jobPrefix + "Started %f seconds of scheduling thinktime."
        .format(jobThinkTime))
      simulator.afterDelay(jobThinkTime){
        if(interruptElasticScheduling){
          simulator.logger.info(schedulerPrefix + elasticPrefix + jobPrefix + "Scheduling must be interrupted because " +
            "a the moldable services of another job are being scheduled.")
          interruptElasticScheduling = false
          schedulingElastic = false
        }else{
          val idx = elasticPendingQueue.indexOf(job)
          if(idx == -1){
            simulator.logger.info(schedulerPrefix + elasticPrefix + jobPrefix + "Was removed from the queue during the thinking time. " +
              "Do not process it.")
          }else{
            var unscheduledTasks: Int = job.elasticTasksUnscheduled
            var elasticTasksLaunched = 0

            simulator.logger.info((schedulerPrefix + elasticPrefix + jobPrefix + "Finished %f seconds of scheduling " +
              "thinktime; checking resources for %d tasks with %f cpus and %f mem each.")
              .format(jobThinkTime,
                unscheduledTasks,
                job.cpusPerTask,
                job.memPerTask))
            job.numSchedulingAttempts += 1
            job.numTaskSchedulingAttempts += unscheduledTasks

            assert(unscheduledTasks > 0)
            val claimDeltas = scheduleJob(job, privateCellState, elastic = true)
            if(claimDeltas.nonEmpty) {
              val commitResult = simulator.cellState.commit(claimDeltas)
              if(commitResult.committedDeltas.nonEmpty){
                recordUsefulTimeScheduling(job, jobThinkTime, job.numSchedulingAttempts == 1)

                elasticTasksLaunched = commitResult.committedDeltas.size
                unscheduledTasks -= elasticTasksLaunched
                job.claimDeltas ++= commitResult.committedDeltas
                job.elasticTasksUnscheduled = unscheduledTasks
                numSuccessfulTransactions += 1

                simulator.logger.info(schedulerPrefix + elasticPrefix + jobPrefix + "Scheduled %d tasks, %d remaining."
                  .format(claimDeltas.size, job.unscheduledTasks))
              }else{
                numFailedTransactions += 1
                simulator.logger.info(schedulerPrefix + elasticPrefix + jobPrefix + "There was a conflict when committing the task allocation to the real cell.")
                recordWastedTimeScheduling(job, jobThinkTime, job.numSchedulingAttempts == 1)
              }

            } else {
              recordWastedTimeScheduling(job, jobThinkTime, job.numSchedulingAttempts == 1)
              numNoResourcesFoundSchedulingAttempts += 1

              simulator.logger.info((schedulerPrefix + elasticPrefix + jobPrefix + "No tasks scheduled (%f cpu %f mem per task) " +
                "during this scheduling attempt, recording " +
                "wasted time. %d unscheduled tasks remaining.")
                .format(job.cpusPerTask,
                  job.memPerTask,
                  unscheduledTasks))
            }

            if (elasticTasksLaunched > 0) {
              var jobLeftDuration: Double = (job.jobFinishedWorking - simulator.currentTime) -
                (elasticTasksLaunched * job.elasticTaskSpeedUpContribution)
              if(jobLeftDuration < job.taskDuration)
                jobLeftDuration = job.taskDuration
              job.jobFinishedWorking = simulator.currentTime + jobLeftDuration

              // We have to remove all the incoming simulation events that work on this job.
              simulator.removeIf(x => x.itemId == job.id &&
                (x.eventType == EventTypes.Remove || x.eventType == EventTypes.Trigger))

              simulator.logger.info(schedulerPrefix + elasticPrefix + jobPrefix + "Adding finished event after %f seconds to wake up scheduler.".format(jobLeftDuration))
              simulator.cellState.scheduleEndEvents(job.claimDeltas, delay = jobLeftDuration, jobId = job.id)
              simulator.afterDelay(jobLeftDuration, eventType = EventTypes.Trigger, itemId = job.id) {
                simulator.logger.info(schedulerPrefix + jobPrefix + "Completed.")
                moldableQueueIterator = pendingQueueAsList.iterator
                // Remove the Job from the elastic queue, if it was present.
                removeElasticJob(job)
                interruptElasticScheduling = true
                scheduleNextJob()
              }
            }

            // If the job isn't yet fully scheduled, put it back in the queue.
            if (unscheduledTasks > 0) {
              simulator.logger.info((schedulerPrefix + elasticPrefix + jobPrefix + "Not fully scheduled, %d / %d tasks remain " +
                "(shape: %f cpus, %f mem per task). Leaving it " +
                "in the queue").format(unscheduledTasks,
                job.elasticTasks,
                job.cpusPerTask,
                job.memPerTask))
            } else {
              simulator.logger.info((schedulerPrefix + elasticPrefix + jobPrefix + "Fully-Scheduled (%f cpu %f mem per task), " +
                "after %d scheduling attempts.").format(job.cpusPerTask,
                job.memPerTask,
                job.numSchedulingAttempts))
              removeElasticJob(job)
            }
          }
          schedulingElastic = false
          // Call scheduleNextElasticJob only if we have no new job(s)
          if(pendingQueueAsList.size <= moldableQueueLength )
            scheduleNextElasticJob()
          else
            scheduleNextJob()
        }
      }
    }
  }
}

//class ForkableIterator[A] (list: scala.collection.mutable.ListBuffer[A]) extends Iterator[A] {
//  private[this] var these = list
//  def hasNext: Boolean = these.nonEmpty
//  def next: A =
//    if (hasNext) {
//      val result = these.head; these = these.tail; result
//    } else Iterator.empty.next
//  def fork = new ForkableIterator(these)
//}
