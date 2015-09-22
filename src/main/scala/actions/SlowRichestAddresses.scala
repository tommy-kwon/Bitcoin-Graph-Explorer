package actions

import util._
import core._
import scala.slick.jdbc.{StaticQuery => Q}
import scala.slick.jdbc.JdbcBackend.Database.dynamicSession

/**
 * Created by yzark on 22.12.14.
 */
object SlowRichestAddresses extends BitcoinDB {
  def apply = {

    println("DEBUG: Calculating richest address list...")
    val startTIme = System.currentTimeMillis
    /**
      * Created by yzark on 15.12.14.
      */
    transactionDBSession {
      Q.updateNA( """
       insert
        into richest_addresses
       select
        (select max(block_height) from blocks) as block_height,
       address,
        balance
      from
        balances
      order by
        balance desc
      limit 1000
    ;""").execute

      println("DONE: Richest address list calculated in " + (System.currentTimeMillis - startTIme)/1000 + "s")
    }
  }
}
