import actions._
import util._
import sys.process._
import collection.mutable.Map

/**
 * Created with IntelliJ IDEA.
 * User: yzark
 * Date: 11/19/1
 * Time: 12:36 PM
 * To change this template use File | Settings | File Templates.
 */
object Explorer extends App with db.BitcoinDB {
  args.toList match{
    case "start"::rest =>
      
      if (!statsDone || rest.headOption == Some("--force"))
        populate
      Seq("touch",lockFile).!
      iterateResume

    case "populate"::rest             =>

      populate

    case "resume"::rest =>

      Seq("touch",lockFile).!
      iterateResume

    case "stop"::rest =>

      Seq("rm",lockFile).!

    case "info"::rest =>

      getInfo

    case _=>

      println("""

        Available commands:

         start [--force]: populate if necessary or --force, then resume
         stop: ask bge gently to stop at the end of the current iteration. Note that thís can take a few days if you are in the populate phase.
         populate: create the database movements with movements and closures.  Deletes any existing data.
         resume: update the database generated by populate with new incoming data. Works only if populate is already done.
         info
      """)
  }

  def getInfo = {
    val (count, amount) = sumUTXOs
    println("Sum of the utxos saved in the lmdb: "+ amount)
    println("Total utxos in the lmdb: " + count)
    val (countDB, amountDB) = countUTXOs
    println("Sum of the utxos in the sql db " +amountDB)
    println("Total utxos in the sql db " + countDB)
  }

  def totalExpectedSatoshi(blockCount: Int): Long = {
    val epoch = blockCount/210000
    val blocksSinceEpoch = blockCount % 210000
    def blockReward(epoch: Int) = Math.floor(50L*100000000L/Math.pow(2,epoch)).toLong
    val fullEpochs = for (i <- (0 until epoch))
                     yield 210000L * blockReward(i)
    val correct = fullEpochs.sum + blocksSinceEpoch * blockReward(epoch)
    correct - blockReward(0) * (if (blockCount > 91880) 2 else if (blockCount > 191842) 1 else 0)
    // correct for the two duplicate coinbase tx (see BIP 30) that we just store once (they are unspendable anyway)
  }

  def sumUTXOs = {
    lazy val table = LmdbMap.open("utxos")
    lazy val outputMap: UTXOs = new UTXOs (table)
    // (txhash,index) -> (address,value,blockIn)
    val values = for ( (_,(_,value,_)) <- outputMap.view) yield value //makes it a lazy collection
    val tuple = values.grouped(100000).foldLeft((0,0L)){
      case ((count,sum),group) =>
        log.info(count + " elements read at ")
        val seq = group.toSeq
        (count+seq.size,sum+seq.sum)
    }

    table.close
    tuple
  }

  def populate = {

    val dataDirectory = new java.io.File(dataDir)

    if (!dataDirectory.isDirectory)
      dataDirectory.mkdir

    initializeReaderTables
    initializeClosureTables
    initializeStatsTables

    insertStatistics
 
    PopulateBlockReader
  
    createIndexes
    new PopulateClosure(PopulateBlockReader.processedBlocks)
    createAddressIndexes    
    populateStats
//    testValues

  }

  def resume = {
    val read = new ResumeBlockReader
    val closure = new ResumeClosure(read.processedBlocks)
    log.info("making new stats")
    resumeStats(read.changedAddresses, closure.changedReps, closure.addedAds, closure.addedReps)
  }

  def iterateResume = {
    // Seq("bitcoind","-daemon").run
    
    if (!peerGroup.isRunning) startBitcoinJ

    // if there are more stats than blocks we could delete it
    for (block <- getWrongBlock){
      
      rollBack(block)
      populateStats 
      assert(getWrongBlock == None, "The database is inconsistent. See logs for details.")
    }

    while (new java.io.File(lockFile).exists)
    {
      if (blockCount > chain.getBestChainHeight)
      {
        log.info("waiting for new blocks")
        chain.getHeightFuture(blockCount).get //wait until the chain overtakes our DB
      }
      resume
    }

    log.info("process stopped")
  }

  def getWrongBlock: Option[Int] = {
    
    val (count,amount) = sumUTXOs
    val (countDB, amountDB) = countUTXOs
    val bc = blockCount
    val expected = totalExpectedSatoshi(bc)
    val utxosMaxHeight = getUtxosMaxHeight 
    
    val lch = lastCompletedHeight
    val sameCount = count == countDB
    val sameValue = amount == amountDB
    val rightValue = amount <= expected
    val rightBlock = (blockCount - 1 == lch) && utxosMaxHeight == lch

    if (!rightValue)  log.error("we have " + ((amount-expected)/100000000.0) + " too many bitcoins")
    if (!sameCount)   log.error("we lost utxos")
    if (!sameValue)   log.error("different sum of btcs in db and lmdb")
    if (!rightBlock)  log.error("wrong or incomplete block")

    if (sameCount && sameValue && rightValue && rightBlock) None
    else if (utxosMaxHeight > bc -1) Some(utxosMaxHeight)
    else if (bc - 1 > lch) Some(bc-1)
    else throw new Exception("This should not have happened. See logs for details.")
  }

  def resumeStats(changedAddresses: Map[Hash,Long], changedReps: Map[Hash,Set[Hash]], addedAds: Int, addedReps: Int)  = {
    
    log.info(changedAddresses.size + " addresses changed balance")

    if (changedAddresses.size < 38749 )
    {
      updateBalanceTables(changedAddresses, changedReps)
      insertRichestAddresses
      insertRichestClosures
      updateStatistics(changedReps,addedAds, addedReps)
    }
    else populateStats
        
  }

  def populateStats = {
    createBalanceTables
    insertRichestAddresses
    insertRichestClosures
    insertStatistics
  }


}
