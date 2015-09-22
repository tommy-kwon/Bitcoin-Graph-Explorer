package actions

import util._
import core.BitcoinDB
import scala.slick.jdbc.{StaticQuery => Q}
import scala.slick.jdbc.JdbcBackend.Database.dynamicSession

/**
  * Created by yzark on 22.12.14.
  */
object SlowRichestClosures extends BitcoinDB {
  def apply = {
    println("DEBUG: Calculating richest closure list...")
    val startTIme = System.currentTimeMillis
    /**
      * Created by yzark on 15.12.14.
      */
    transactionDBSession {
      Q.updateNA( """
      insert
        into richest_closures
      select
        (select max(block_height) from blocks) as block_height,
        representant as address,
        balance
      from
        closure_balances
      order by
        balance desc
      limit 1000
      ;""").execute

      println("DONE: Richest closure list calculated in " + (System.currentTimeMillis - startTIme)/1000 + "s")
    }
  }
}
