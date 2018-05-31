package filodb.core.binaryrecord2

import com.typesafe.scalalogging.StrictLogging
import scalaxy.loops._

import filodb.core.metadata.Column
import filodb.memory.{BinaryRegion, MemFactory, UTF8StringMedium}
import filodb.memory.format.{RowReader, SeqRowReader, UnsafeUtils, ZeroCopyUTF8String => ZCUTF8}

/**
 * A RecordBuilder allocates fixed size containers and builds BinaryRecords within them.
 * The size of the container should be much larger than the average size of a record for efficiency.
 * Many BinaryRecords are built within one container.
 * This is very much a mutable, stateful class and should be run within a single thread or stream of execution.
 * It is NOT multi-thread safe.
 * The idea is to use one RecordBuilder per context/stream/thread. The context should make sense; as the list of
 * containers can then be 1) sent over the wire, with no further transformations needed, 2) obtained and maybe freed
 *
 * @param memFactory the MemFactory used to allocate containers for building BinaryRecords in
 * @param schema the RecordSchema for the BinaryRecord to build
 * @param containerSize the size of each container
 */
final class RecordBuilder(memFactory: MemFactory,
                          val schema: RecordSchema,
                          containerSize: Int = RecordBuilder.DefaultContainerSize,
                          // Put fields here so scalac won't generate stupid and slow initialization check logic
                          // Seriously this makes var updates like twice as fast
                          private var curBase: Any = UnsafeUtils.ZeroPointer,
                          private var fieldNo: Int = -1,
                          private var curRecordOffset: Long = -1L,
                          private var curRecEndOffset: Long = -1L,
                          private var maxOffset: Long = -1L,
                          private var mapOffset: Long = -1L,
                          private var recHash: Int = -1) extends StrictLogging {

  import RecordBuilder._
  import UnsafeUtils._
  require(containerSize >= RecordBuilder.MinContainerSize, s"RecordBuilder.containerSize < minimum")

  private val containers = new collection.mutable.ArrayBuffer[RecordContainer]
  private val firstPartField = schema.partitionFieldStart.getOrElse(Int.MaxValue)
  private val hashOffset = schema.fieldOffset(schema.numFields)

  def reset(): Unit = if (containers.nonEmpty) {
    curRecordOffset = containers.last.offset + ContainerHeaderLen
    curRecEndOffset = curRecordOffset
    fieldNo = -1
    mapOffset = -1L
    recHash = -1
  }

  /**
   * Start building a new BinaryRecord.
   * This must be called after a previous endRecord() or when the builder just started.
   */
  final def startNewRecord(): Unit = {
    require(curRecEndOffset == curRecordOffset, s"Illegal state: $curRecEndOffset != $curRecordOffset")
    requireBytes(schema.variableAreaStart)

    // write length header and update RecEndOffset
    setInt(curBase, curRecordOffset, schema.variableAreaStart - 4)
    curRecEndOffset = curRecordOffset + schema.variableAreaStart

    fieldNo = 0
    recHash = 7
  }

  /**
   * Adds an integer to the record.  This must be called in the right order or the data might be corrupted.
   * Also this must be called between startNewRecord and endRecord.
   * Calling this method after all fields of a record has been filled will lead to an error.
   */
  final def addInt(data: Int): Unit = {
    checkFieldNo()
    setInt(curBase, curRecordOffset + schema.fieldOffset(fieldNo), data)
    fieldNo += 1
  }

  /**
   * Adds a Long to the record.  This must be called in the right order or the data might be corrupted.
   * Also this must be called between startNewRecord and endRecord.
   * Calling this method after all fields of a record has been filled will lead to an error.
   */
  final def addLong(data: Long): Unit = {
    checkFieldNo()
    setLong(curBase, curRecordOffset + schema.fieldOffset(fieldNo), data)
    fieldNo += 1
  }

  /**
   * Adds a Double to the record.  This must be called in the right order or the data might be corrupted.
   * Also this must be called between startNewRecord and endRecord.
   * Calling this method after all fields of a record has been filled will lead to an error.
   */
  final def addDouble(data: Double): Unit = {
    checkFieldNo()
    setDouble(curBase, curRecordOffset + schema.fieldOffset(fieldNo), data)
    fieldNo += 1
  }

  /**
   * Adds a string or raw bytes to the record.  They must fit in within 64KB.
   * The variable length area of the BinaryRecord will be extended.
   */
  final def addString(bytes: Array[Byte]): Unit = {
    require(bytes.size < 65536, s"bytes too large (${bytes.size} bytes) for addString")
    checkFieldAndMemory(bytes.size + 2)
    UTF8StringMedium.copyByteArrayTo(bytes, curBase, curRecEndOffset)
    updateFieldPointerAndLens(bytes.size + 2)
    if (fieldNo >= firstPartField) recHash = combineHash(recHash, BinaryRegion.hash32(bytes))
    fieldNo += 1
  }

  final def addString(s: String): Unit = addString(s.getBytes)

  import Column.ColumnType._

  /**
   * A SLOW but FLEXIBLE method to add data to the current field.  Boxes for sure but can take any data.
   * Relies on passing in an object (Any) and using match, lots of allocations here.
   * PLEASE don't use it in high performance code / hot paths.  Meant for ease of testing.
   */
  def addSlowly(item: Any): Unit = {
    (schema.columnTypes(fieldNo), item) match {
      case (IntColumn, i: Int)       => addInt(i)
      case (LongColumn, l: Long)     => addLong(l)
      case (DoubleColumn, d: Double) => addDouble(d)
      case (StringColumn, s: String) => addString(s)
      case (StringColumn, a: Array[Byte]) => addString(a)
      case (StringColumn, z: ZCUTF8) => addString(z.toString)
      case (MapColumn, m: Map[ZCUTF8, ZCUTF8] @unchecked) =>
        val pairs = new java.util.ArrayList[(String, String)]
        m.toSeq.foreach { case (k, v) => pairs.add((k.toString, v.toString)) }
        val hashes = sortAndComputeHashes(pairs)
        addSortedPairsAsMap(pairs, hashes)
      case (other: Column.ColumnType, v) =>
        throw new UnsupportedOperationException(s"Column type of $other and value of class ${v.getClass}")
    }
  }

  /**
   * Adds an entire record from a RowReader using getAny.  SUPER SLOW since it uses addSlowly.  Mostly for testing.
   * @return the offset or NativePointer if the memFactory is an offheap one, to the new BinaryRecord
   */
  def addFromReaderSlowly(row: RowReader): Long = {
    startNewRecord()
    (0 until schema.numFields).foreach { pos =>
      addSlowly(schema.columnTypes(pos).keyType.extractor.getField(row, pos))
    }
    endRecord()
  }

  // Really only for testing. Very slow.
  def addFromObjects(parts: Any*): Long = addFromReaderSlowly(SeqRowReader(parts.toSeq))

  /**
   * High level function to add a sorted list of unique key-value pairs as a map in the BinaryRecord.
   * @param sortedPairs sorted list of key-value pairs, as modified by sortAndComputeHashes
   * @param hashes an array of hashes, one for each k-v pair, as returned by sortAndComputeHashes
   */
  def addSortedPairsAsMap(sortedPairs: java.util.ArrayList[(String, String)],
                          hashes: Array[Int]): Unit = {
    startMap()
    for { i <- 0 until sortedPairs.size optimized } {
      val (k, v) = sortedPairs.get(i)
      addMapKeyValue(k.getBytes, v.getBytes)
      recHash = combineHash(recHash, hashes(i))
    }
    endMap()
  }

  /**
   * Low-level function to start adding a map field.  Must be followed by addMapKeyValue() in sorted order of
   * keys (UTF8 byte sort).  Might want to use one of the higher level functions.
   */
  final def startMap(): Unit = {
    require(mapOffset == -1L)
    checkFieldAndMemory(4)   // 4 bytes for map length header
    mapOffset = curRecEndOffset
    setInt(curBase, mapOffset, 0)
    updateFieldPointerAndLens(4)
    // Don't update fieldNo, we'll be working on map for a while
  }

  /**
   * Adds a single key-value pair to the map field started by startMap().
   * Takes care of matching and translating predefined keys into short codes.
   * Keys must be < 60KB and values must be < 64KB
   */
  final def addMapKeyValue(key: Array[Byte], value: Array[Byte]): Unit = {
    require(mapOffset > curRecordOffset, "illegal state, did you call startMap() first?")
    // check key size, must be < 60KB
    require(key.size < 60*1024, s"key is too large: ${key.size} bytes")
    require(value.size < 64*1024, s"value is too large: ${key.size} bytes")

    // Check if key is a predefined key
    val predefKeyNum = schema.predefKeyNumMap.getOrElse(RecordSchema.makeKeyKey(key), -1)
    val keyValueSize = if (predefKeyNum >= 0) { value.size + 4 } else { key.size + value.size + 4 }
    requireBytes(keyValueSize)
    if (predefKeyNum >= 0) {
      setShort(curBase, curRecEndOffset, (0xF000 | predefKeyNum).toShort)
      curRecEndOffset += 2
    } else {
      UTF8StringMedium.copyByteArrayTo(key, curBase, curRecEndOffset)
      curRecEndOffset += key.size + 2
    }
    UTF8StringMedium.copyByteArrayTo(value, curBase, curRecEndOffset)
    curRecEndOffset += value.size + 2

    // update map length, BR length
    setInt(curBase, mapOffset, (curRecEndOffset - mapOffset - 4).toInt)
    setInt(curBase, curRecordOffset, (curRecEndOffset - curRecordOffset - 4).toInt)
  }

  /**
   * Ends creation of a map field
   */
  def endMap(): Unit = {
    mapOffset = -1L
    fieldNo += 1
  }

  /**
   * Ends the building of the current BinaryRecord.  Makes sures RecordContainer state is updated.
   * Aligns the next record on a 4-byte/short word boundary.
   * Returns the Long offset of the just finished BinaryRecord.  If the container is offheap, then this is the
   * full NativePointer.  If it is onHeap, you will need to access the current container and get the base
   * to form the (base, offset) pair needed to access the BinaryRecord.
   */
  final def endRecord(writeHash: Boolean = true): Long = {
    val recordOffset = curRecordOffset

    if (writeHash) setInt(curBase, curRecordOffset + hashOffset, recHash)

    // Bring RecordOffset up to endOffset w/ align.  Now the state is complete at end of a record again.
    curRecEndOffset = align(curRecEndOffset)
    curRecordOffset = curRecEndOffset
    fieldNo = -1

    // Update container length.  This is atomic so it is updated only when the record is complete.
    containers.last.updateLengthWithOffset(curRecEndOffset)

    recordOffset
  }

  final def align(offset: Long): Long = (offset + 3) & ~3

  private def matchBytes(bytes1: Array[Byte], bytes2: Array[Byte]): Boolean =
    bytes1.size == bytes2.size &&
    UnsafeUtils.equate(bytes1, UnsafeUtils.arayOffset, bytes2, UnsafeUtils.arayOffset, bytes1.size)

  /**
   * Used only internally by RecordComparator etc. to shortcut create a new BR by copying bytes from an existing BR.
   * You BETTER know what you are doing.
   */
  private[binaryrecord2] def copyNewRecordFrom(base: Any, offset: Long, numBytes: Int): Unit = {
    require(curRecEndOffset == curRecordOffset, s"Illegal state: $curRecEndOffset != $curRecordOffset")
    requireBytes(numBytes + 4)

    // write length header, copy bytes, and update RecEndOffset
    setInt(curBase, curRecordOffset, numBytes)
    UnsafeUtils.unsafe.copyMemory(base, offset, curBase, curRecordOffset + 4, numBytes)
    curRecEndOffset = curRecordOffset + numBytes + 4
  }

  private[binaryrecord2] def adjustFieldOffset(fieldNo: Int, adjustment: Int): Unit = {
    val offset = curRecordOffset + schema.fieldOffset(fieldNo)
    UnsafeUtils.setInt(curBase, offset, UnsafeUtils.getInt(curBase, offset) + adjustment)
  }

  // resets or empties current container.  Only used for testing.  Also ensures there's at least one container
  private[filodb] def resetCurrent(): Unit = {
    if (containers.isEmpty) requireBytes(100)
    curBase = currentContainer.get.base
    curRecordOffset = currentContainer.get.offset + 4
    currentContainer.get.updateLengthWithOffset(curRecordOffset)
    curRecEndOffset = curRecordOffset
  }

  /**
   * Returns Some(container) reference to the current RecordContainer or None if there is no container
   */
  def currentContainer: Option[RecordContainer] = containers.lastOption

  /**
   * Returns the list of all current containers
   */
  def allContainers: Seq[RecordContainer] = containers

  /**
   * Returns the containers as byte arrays.  Assuming all of the containers except the last one is full,
   * calls array() on the non-last container and trimmedArray() on the last one.
   * The memFactory needs to be an on heap one otherwise UnsupportedOperationException will be thrown.
   * The sequence of byte arrays can be for example sent to Kafka as a sequence of messages - one message
   * per byte array.
   * @param reset if true, clears out all the containers.  Allows a producer of containers to obtain the
   *              byte arrays for sending somewhere else, while clearing containers for the next batch.
   */
  def optimalContainerBytes(reset: Boolean = false): Seq[Array[Byte]] = {
    val bytes = allContainers.dropRight(1).map(_.array) ++ Seq(currentContainer.get.trimmedArray)
    if (reset) containers.clear()
    bytes
  }

  /**
   * Returns the number of free bytes in the current container, or 0 if container is not initialized
   */
  def containerRemaining: Long = maxOffset - curRecEndOffset

  private def requireBytes(numBytes: Int): Unit =
    // if container is none, allocate a new one, make sure it has enough space, reset length, update offsets
    if (containers.isEmpty) {
      newContainer()
    // if we don't have enough space left, get a new container and move existing BR being written into new space
    } else if (curRecEndOffset + numBytes > maxOffset) {
      val oldBase = curBase
      val recordNumBytes = curRecEndOffset - curRecordOffset
      val oldOffset = curRecordOffset
      newContainer()
      logger.debug(s"Moving $recordNumBytes bytes from end of old container to new container")
      require((containerSize - ContainerHeaderLen) > (recordNumBytes + numBytes), "Record too big for container")
      unsafe.copyMemory(oldBase, oldOffset, curBase, curRecordOffset, recordNumBytes)
      curRecEndOffset = curRecordOffset + recordNumBytes
    }

  private def newContainer(): Unit = {
    val (newBase, newOff, _) = memFactory.allocate(containerSize)
    val container = new RecordContainer(newBase, newOff, containerSize)
    containers += container
    logger.debug(s"Creating new RecordContainer with $containerSize bytes using $memFactory")
    curBase = newBase
    curRecordOffset = newOff + ContainerHeaderLen
    curRecEndOffset = curRecordOffset
    container.updateLengthWithOffset(curRecordOffset)
    container.writeVersionWord()
    maxOffset = newOff + containerSize
  }

  private def checkFieldNo(): Unit = require(fieldNo >= 0 && fieldNo < schema.numFields)

  private def checkFieldAndMemory(bytesRequired: Int): Unit = {
    checkFieldNo()
    requireBytes(bytesRequired)
  }

  private def updateFieldPointerAndLens(varFieldLen: Int): Unit = {
    // update fixed field area, which is a 4-byte offset to the var field
    setInt(curBase, curRecordOffset + schema.fieldOffset(fieldNo), (curRecEndOffset - curRecordOffset).toInt)
    curRecEndOffset += varFieldLen

    // update BinaryRecord length header as well
    setInt(curBase, curRecordOffset, (curRecEndOffset - curRecordOffset).toInt - 4)
  }
}

object RecordBuilder {
  import collection.JavaConverters._

  val DefaultContainerSize = 256 * 1024
  val MinContainerSize = 2048

  // Please do not change this.  It should only be changed with a change in BinaryRecord and/or RecordContainer
  // format, and only then REALLY carefully.
  val Version = 0
  val ContainerHeaderLen = 8

  val stringPairComparator = new java.util.Comparator[(String, String)] {
    def compare(pair1: (String, String), pair2: (String, String)): Int = pair1._1 compare pair2._1
  }

  /**
    * Make is a convenience factory method to access from java.
    */
  def make(memFactory: MemFactory,
           schema: RecordSchema,
           containerSize: Int = RecordBuilder.DefaultContainerSize): RecordBuilder = {
    new RecordBuilder(memFactory, schema, containerSize)
  }

  /**
    * == Auxiliary functions to compute hashes. ==
    */

  /**
    * Sorts an incoming list of key-value pairs and then computes a hash value
    * for each pair.  The output can be fed into the combineHash methods to produce an overall hash.
    * @param pairs an unsorted list of key-value pairs.  Will be mutated and sorted.
    */
  final def sortAndComputeHashes(pairs: java.util.ArrayList[(String, String)]): Array[Int] = {
    pairs.sort(stringPairComparator)
    val hashes = new Array[Int](pairs.size)
    for { i <- 0 until pairs.size optimized } {
      val (k, v) = pairs.get(i)
      hashes(i) = combineHash(k.hashCode, v.hashCode)
    }
    hashes
  }

  @inline
  final def combineHash(hash1: Int, hash2: Int): Int = 31 * hash1 + hash2

  /**
    * Combines the hashes from sortAndComputeHashes, excluding certain keys, into an overall hash value.
    * @param sortedPairs sorted pairs of byte key values, from sortAndComputeHashes
    * @param hashes the output from sortAndComputeHashes
    * @param excludeKeys set of String keys to exclude
    */
  final def combineHashExcluding(sortedPairs: java.util.ArrayList[(String, String)],
                                 hashes: Array[Int],
                                 excludeKeys: Set[String]): Int = {
    var hash = 7
    for { i <- 0 until sortedPairs.size optimized } {
      if (!(excludeKeys contains sortedPairs.get(i)._1))
        hash = combineHash(hash, hashes(i))
    }
    hash
  }

  /**
    * Combines the hashes from sortAndComputeHashes, only including certain keys, into an overall hash value.
    * All the keys from includeKeys must be present.  Usually used to compute the shard key hash.
    * @param pairs sorted pairs of byte key values, from sortAndComputeHashes
    * @param hashes the output from sortAndComputeHashes
    * @param includeKeys the keys to include
    * @return Some(hash) if all the keys in includeKeys were present, or None
    */
  final def combineHashIncluding(pairs: java.util.ArrayList[(String, String)],
                                 hashes: Array[Int],
                                 includeKeys: Set[String]): Option[Int] = {
    var hash = 7
    var numIncluded = 0
    var index = 0
    while (index < pairs.size && numIncluded < includeKeys.size) {
      if (includeKeys contains pairs.get(index)._1) {
        hash = combineHash(hash, hashes(index))
        numIncluded += 1
      }
      index += 1
    }
    if (numIncluded == includeKeys.size) Some(hash) else None
  }

  /**
   * A convenience function to compute the shard key hash given a list of shard key columns
   * and their corresponding values.  The list must contain all the shard key columns and only those.
   * @param shardKeyColumns the names of shard key columns
   * @param shardKeyValues the values for each shard key column
   */
  final def shardKeyHash(shardKeyColumns: Seq[String], shardKeyValues: Seq[String]): Int = {
    val jList = new java.util.ArrayList(shardKeyColumns.zip(shardKeyValues).asJava)
    val hashes = sortAndComputeHashes(jList)
    combineHashIncluding(jList, hashes, shardKeyColumns.toSet).get
  }
}