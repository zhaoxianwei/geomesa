/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.ingest

import java.io._
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.IOUtils
import org.geotools.data.{DataStoreFinder, DataUtilities, FeatureWriter, Transaction}
import org.geotools.factory.Hints
import org.geotools.filter.identity.FeatureIdImpl
import org.joda.time.format.PeriodFormatterBuilder
import org.locationtech.geomesa.tools.Command
import org.locationtech.geomesa.utils.classpath.PathUtils
import org.locationtech.geomesa.utils.stats.CountingInputStream
import org.locationtech.geomesa.utils.text.TextTools
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.collection.JavaConversions._
import scala.util.Try

/**
  * Base class for handling ingestion of local or distributed files
  *
  * @param dsParams data store parameters
  * @param typeName simple feature type name to ingest
  * @param inputs files to ingest
  * @param libjarsFile file with list of jars needed for ingest
  * @param libjarsPaths paths to search for libjars
  * @param numLocalThreads for local ingest, how many threads to use
  */
abstract class AbstractIngest(val dsParams: Map[String, String],
                              typeName: String,
                              inputs: Seq[String],
                              libjarsFile: String,
                              libjarsPaths: Iterator[() => Seq[File]],
                              numLocalThreads: Int) extends Runnable with LazyLogging {

  import AbstractIngest._

  /**
   * Setup hook - called before run method is executed
   */
  def beforeRunTasks(): Unit

  /**
   * Create a local ingestion converter
   *
   * @param file file being operated on
   * @param failures used to tracks failures
   * @return local converter
   */
  def createLocalConverter(file: File, failures: AtomicLong): LocalIngestConverter

  /**
    * Run a distributed ingestion
    *
    * @param statusCallback for reporting status
    * @return (success, failures) counts
    */
  def runDistributedJob(statusCallback: StatusCallback): (Long, Long)

  protected val ds = DataStoreFinder.getDataStore(dsParams)

  /**
   * Main method to kick off ingestion
   */
  override def run(): Unit = {
    beforeRunTasks()
    val distPrefixes = Seq("hdfs://", "s3n://", "s3a://", "wasb://", "wasbs://")
    if (distPrefixes.exists(inputs.head.toLowerCase.startsWith)) {
      Command.user.info("Running ingestion in distributed mode")
      runDistributed()
    } else {
      Command.user.info("Running ingestion in local mode")
      runLocal()
    }
    ds.dispose()
  }

  private def runLocal(): Unit = {

    // Global failure shared between threads
    val (written, failed) = (new AtomicLong(0), new AtomicLong(0))

    val bytesRead = new AtomicLong(0L)

    class LocalIngestWorker(file: File) extends Runnable {
      override def run(): Unit = {
        try {
          // only create the feature writer after the converter runs
          // so that we can create the schema based off the input file
          var fw: FeatureWriter[SimpleFeatureType, SimpleFeature] = null
          val converter = createLocalConverter(file, failed)
          // count the raw bytes read from the file, as that's what we based our total on
          val countingStream = new CountingInputStream(new FileInputStream(file))
          val is = PathUtils.handleCompression(countingStream, file.getPath)
          try {
            val (sft, features) = converter.convert(is)
            if (features.hasNext) {
              ds.createSchema(sft)
              fw = ds.getFeatureWriterAppend(typeName, Transaction.AUTO_COMMIT)
            }
            features.foreach { sf =>
              val toWrite = fw.next()
              toWrite.setAttributes(sf.getAttributes)
              toWrite.getIdentifier.asInstanceOf[FeatureIdImpl].setID(sf.getID)
              toWrite.getUserData.putAll(sf.getUserData)
              toWrite.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
              try {
                fw.write()
                written.incrementAndGet()
              } catch {
                case e: Exception =>
                  logger.error(s"Failed to write '${DataUtilities.encodeFeature(toWrite)}'", e)
                  failed.incrementAndGet()
              }
              bytesRead.addAndGet(countingStream.getCount)
              countingStream.resetCount()
            }
          } finally {
            IOUtils.closeQuietly(converter)
            IOUtils.closeQuietly(is)
            IOUtils.closeQuietly(fw)
          }
        } catch {
          case e: Exception =>
            // Don't kill the entire program bc this thread was bad! use outer try/catch
            logger.error(s"Fatal error running local ingest worker on file ${file.getPath}", e)
        }
      }
    }

    val files = inputs.flatMap(PathUtils.interpretPath)
    val numFiles = files.length
    val totalLength = files.map(_.length).sum.toFloat

    def progress(): Float = bytesRead.get() / totalLength

    Command.user.info(s"Ingesting ${TextTools.getPlural(numFiles, "file")} with ${TextTools.getPlural(numLocalThreads, "thread")}")

    val start = System.currentTimeMillis()
    val statusCallback = createCallback()
    val es = Executors.newFixedThreadPool(numLocalThreads)
    files.foreach(f => es.submit(new LocalIngestWorker(f)))
    es.shutdown()

    def counters = Seq(("ingested", written.get()), ("failed", failed.get()))

    while (!es.isTerminated) {
      Thread.sleep(1000)
      statusCallback("", progress(), counters, done = false)
    }
    statusCallback("", progress(), counters, done = true)

    Command.user.info(s"Local ingestion complete in ${TextTools.getTime(start)}")
    Command.user.info(getStatInfo(written.get, failed.get))
  }

  private def runDistributed(): Unit = {
    val start = System.currentTimeMillis()
    val statusCallback = createCallback()
    val (success, failed) = runDistributedJob(statusCallback)
    Command.user.info(s"Distributed ingestion complete in ${TextTools.getTime(start)}")
    Command.user.info(getStatInfo(success, failed))
  }

  private def createCallback(): StatusCallback = {
    if (dsParams.get("useMock").exists(_.toBoolean)) {
      new PrintProgress(System.err, TextTools.buildString('\u26AC', 60), ' ', '\u15e7', '\u2b58')
    } else {
      new PrintProgress(System.err, TextTools.buildString(' ', 60), '\u003d', '\u003e', '\u003e')
    }
  }
}

object AbstractIngest {

  val PeriodFormatter =
    new PeriodFormatterBuilder().minimumPrintedDigits(2).printZeroAlways()
      .appendHours().appendSeparator(":").appendMinutes().appendSeparator(":").appendSeconds().toFormatter

  lazy private val terminalWidth: () => Float = {
    val jline = for {
      terminalClass <- Try(Class.forName("jline.Terminal"))
      terminal      <- Try(terminalClass.getMethod("getTerminal").invoke(null))
      method        <- Try(terminalClass.getMethod("getTerminalWidth"))
    } yield {
      () => method.invoke(terminal).asInstanceOf[Int].toFloat
    }
    jline.getOrElse(() => 1.0f)
  }

  /**
   * Gets status as a string
   */
  def getStatInfo(successes: Long, failures: Long): String = {
    val failureString = if (failures == 0) {
      "with no failures"
    } else {
      s"and failed to ingest ${TextTools.getPlural(failures, "feature")}"
    }
    s"Ingested ${TextTools.getPlural(successes, "feature")} $failureString."
  }

  sealed trait StatusCallback {
    def reset(): Unit
    def apply(prefix: String, progress: Float, counters: Seq[(String, Long)], done: Boolean): Unit
  }

  /**
    * Prints progress using the provided output stream. Progress will be overwritten using '\r', and will only
    * include a line feed if done == true
    */
  class PrintProgress(out: PrintStream, emptyBar: String, replacement: Char, indicator: Char, toggle: Char)
      extends StatusCallback {

    private var toggled = false
    private var start = System.currentTimeMillis()

    override def reset(): Unit = start = System.currentTimeMillis()

    override def apply(prefix: String, progress: Float, counters: Seq[(String, Long)], done: Boolean): Unit = {
      val percent = f"${(progress * 100).toInt}%3d"
      val counterString = if (counters.isEmpty) { "" } else {
        counters.map { case (label, count) => s"$count $label"}.mkString(" ", " ", "")
      }
      val info = s" $percent% complete$counterString in ${TextTools.getTime(start)}"

      // Figure out if and how much the progress bar should be scaled to accommodate smaller terminals
      val scaleFactor: Float = {
        val tWidth = terminalWidth()
        // Sanity check as jline may not be correct. We also don't scale up, ~112 := scaleFactor = 1.0f
        if (tWidth > info.length + 3 && tWidth < emptyBar.length + info.length + 2 + prefix.length) {
          // Screen Width 80 yields scaleFactor of .46
          (tWidth - info.length - 2 - prefix.length) / emptyBar.length // -2 is for brackets around bar
        } else {
          1.0f
        }
      }

      val scaledLen = (emptyBar.length * scaleFactor).toInt
      val numDone = (scaledLen * progress).toInt
      val bar = if (numDone < 1) {
        emptyBar.substring(emptyBar.length - scaledLen)
      } else if (numDone >= scaledLen) {
        TextTools.buildString(replacement, scaledLen)
      } else {
        val doneStr = TextTools.buildString(replacement, numDone - 1) // -1 for indicator
        val doStr = emptyBar.substring(emptyBar.length - (scaledLen - numDone))
        val i = if (toggled) { toggle } else { indicator }
        toggled = !toggled
        s"$doneStr$i$doStr"
      }

      // use \r to replace current line
      // trailing space separates cursor
      out.print(s"\r$prefix[$bar]$info")
      if (done) {
        out.println()
      }
    }
  }
}

trait LocalIngestConverter extends Closeable {
  def convert(is: InputStream): (SimpleFeatureType, Iterator[SimpleFeature])
}