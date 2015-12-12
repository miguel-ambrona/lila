package lila.tournament

import org.joda.time.DateTime
import play.api.libs.iteratee._
import reactivemongo.bson._
import scala.concurrent.duration._

import chess.variant.Variant
import lila.db.BSON._
import lila.db.Types.Coll

private final class LeaderboardIndexer(
    tournamentColl: Coll,
    leaderboardColl: Coll) {

  import LeaderboardApi._
  import BSONHandlers._

  def generateAll: Funit = leaderboardColl.remove(BSONDocument()) >> {
    tournamentColl.find(TournamentRepo.finishedSelect ++ TournamentRepo.scheduledSelect)
      .sort(BSONDocument("startsAt" -> -1))
      .cursor[Tournament]()
      .enumerate(20 * 1000, stopOnError = true) &>
      Enumeratee.mapM[Tournament].apply[Seq[Entry]](generateTour) &>
      Enumeratee.mapConcat[Seq[Entry]].apply[Entry](identity) &>
      Enumeratee.grouped(Iteratee takeUpTo 500) |>>>
      Iteratee.foldM[Seq[Entry], Int](0) {
        case (number, entries) =>
          if (number % 10000 == 0)
            play.api.Logger("tournament").info(s"Generating leaderboards... $number")
          leaderboardColl.bulkInsert(
            documents = entries.map(BSONHandlers.leaderboardEntryHandler.write).toStream,
            ordered = false) inject (number + entries.size)
      }
  }.void

  private def generateTour(tour: Tournament): Fu[List[Entry]] = tour.schedule ?? { sched =>
    for {
      nbGames <- PairingRepo.countByTourIdAndUserIds(tour.id)
      players <- PlayerRepo.bestByTourWithRank(tour.id, nb = 5000, skip = 0)
      entries <- lila.common.Future.traverseSequentially[RankedPlayer, Option[Entry]](players) {
        case RankedPlayer(rank, player) =>
          tour.system.scoringSystem.sheet(tour, player.userId).map { sheet =>
            for {
              perfType <- tour.perfType
              nb <- nbGames get player.userId
            } yield Entry(
              id = player._id,
              tourId = tour.id,
              userId = player.userId,
              nbGames = nb,
              score = player.score,
              rank = rank,
              rankRatio = Ratio(rank.toDouble / tour.nbPlayers),
              freq = sched.freq,
              speed = sched.speed,
              perf = perfType,
              date = tour.startsAt)
          }
      }.map(_.flatten)
    } yield entries
  }
}
