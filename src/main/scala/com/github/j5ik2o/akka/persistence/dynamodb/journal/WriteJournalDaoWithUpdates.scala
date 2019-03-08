package com.github.j5ik2o.akka.persistence.dynamodb.journal

import akka.NotUsed
import akka.stream.scaladsl.Source

trait WriteJournalDaoWithUpdates extends WriteJournalDao {

  def updateMessage(journalRow: JournalRow): Source[Unit, NotUsed]

}