package info.fotm.clustering

import ClusteringEvaluator.LadderSnapshot
import info.fotm.util.MathVector
import scala.util.Random

case class CharacterId(uid: String)

case class CharacterInfo(
                          id: CharacterId,
                          rating: Int,
                          weeklyWins: Int,
                          weeklyLosses: Int,
                          seasonWins: Int,
                          seasonLosses: Int)

case class CharFeatures(
                         prevInfo: CharacterInfo,
                         nextInfo: CharacterInfo) {
  require(prevInfo.id == nextInfo.id)
}

case class Team(members: Set[CharacterId]) {
  def rating(ladder: LadderSnapshot): Int = {
    val charInfos: Set[CharacterInfo] = members.map(ladder)
    val totalRating = charInfos.toSeq.map(_.rating).sum
    val result = totalRating / members.size.toDouble
    result.toInt
  }
}

object ClusteringEvaluator extends App {

  type LadderSnapshot = Map[CharacterId, CharacterInfo]

  val clusterer: Clusterer = new HTClusterer()
  val ladderSize = 100
  val teamSize = 3
  val matchesPerTurn = 2

  def featurize(ci: CharFeatures): MathVector = MathVector(
    ci.nextInfo.rating - ci.prevInfo.rating,
    ci.nextInfo.rating,
    ci.nextInfo.seasonWins,
    ci.nextInfo.seasonLosses,
    ci.nextInfo.weeklyWins,
    ci.nextInfo.weeklyLosses
  ).normalize

  lazy val rng = new Random()

  def genPlayer = {
    val id = java.util.UUID.randomUUID().toString
    CharacterInfo(CharacterId(id), 1500, 0, 0, 0, 0)
  }

  def genLadder(nPlayers: Int): LadderSnapshot = (0 until nPlayers).map(i => {
    val player = genPlayer
    (player.id, player)
  }).toMap

  type Match = (Team, Team)

  def evaluate(data: Seq[(LadderSnapshot, LadderSnapshot, Set[Match])]): Double = {
    (for {
      (ladder, nextLadder, matches) <- data
    } yield {
      val teamsPlayed: Set[Team] = matches.map(m => m).flatten.toSet
      val playersPlayed: Set[CharacterId] = teamsPlayed.flatMap(_.members)

      // TODO: ATTENTION! Works only as long as MathVector is compared by ref
      // algo input: ladder diffs for playersPlayed
      val diffs: List[CharFeatures] = playersPlayed.toList.map { p => CharFeatures(ladder(p), nextLadder(p)) }
      val featurizedDiffs: List[MathVector] = diffs.map(featurize)
      val featureMap: Map[MathVector, CharacterId] = featurizedDiffs.zip(diffs.map(_.prevInfo.id)).toMap

      // algo output: players grouped into teams
      val algoOutputClusters: Set[clusterer.Cluster] = clusterer.clusterize(featurizedDiffs, teamSize)
      val algoOutputTeams: Set[Team] = algoOutputClusters.map(cluster => Team(cluster.map(featureMap).toSet))

      // algo evaluation: match output against teamsPlayed
      val guessed: Set[Team] = teamsPlayed.intersect(algoOutputTeams)
      val misguessed: Set[Team] = algoOutputTeams -- teamsPlayed
      val missed: Set[Team] = teamsPlayed -- algoOutputTeams
      guessed.size
    }).sum
  }

  def prepareData(ladderOpt: Option[LadderSnapshot] = None, teamsOpt: Option[Seq[Team]] = None)
    : Stream[(LadderSnapshot, LadderSnapshot, Set[Match])] = {
    val ladder: LadderSnapshot = ladderOpt.getOrElse( genLadder(ladderSize) )

    val teams: Seq[Team] = teamsOpt.getOrElse {
      val ids = ladder.keys.toSeq
      val result = ids.sliding(teamSize, teamSize).map(p => Team(p.toSet)).toSeq
      if (result.last.members.size == teamSize) result else result.init
    }

    // TODO: team hopping

    val shuffledTeams: Seq[Team] = new Random().shuffle(teams)

    // TODO: give higher ranked team higher chance of winning

    val matches = shuffledTeams.sliding(2, 2).map{ s => (s(0), s(1)) }.take(matchesPerTurn)

    val nextLadder: LadderSnapshot = matches.foldLeft(ladder) { (l, teams) => play(l, teams._1, teams._2) }

    (ladder, nextLadder, matches.toSet) #:: prepareData(Some(nextLadder), Some(teams))
  }

  def calcRating(charId: CharacterId, team: Team, won: Boolean)(ladder: LadderSnapshot): Int = {
    val teamRating = team.rating(ladder)
    val charInfo = ladder(charId)
    if (won) {
      charInfo.rating + calcRatingChange(charInfo.rating, teamRating)
    } else {
      charInfo.rating - calcRatingChange(teamRating, charInfo.rating)
    }
  }

  def calcRatingChange(winnerRating: Double, loserRating: Double): Int = {
    val chance = 1.0 / (1.0 + Math.pow(10, (loserRating - winnerRating)/400.0))
    val k = 32
    Math.round(k * (1 - chance)).toInt
  }

  def play(currentLadder: LadderSnapshot, wTeam: Team, lTeam: Team): LadderSnapshot = {
    val ladderWithWinners: LadderSnapshot =
      wTeam.members.foldLeft(currentLadder) { (ladder, charId) =>
        val ratingDelta = calcRating(charId, lTeam, won = true)(currentLadder)
        val charInfo = currentLadder(charId)

        val newCharInfo = charInfo.copy(
          rating = ratingDelta,
          weeklyWins = charInfo.weeklyWins + 1,
          seasonWins = charInfo.seasonWins + 1)

        ladder.updated(charInfo.id, newCharInfo)
      }

    val result: LadderSnapshot =
      lTeam.members.foldLeft(ladderWithWinners) { (ladder, charId) =>
        val ratingDelta = calcRating(charId, wTeam, won = false)(currentLadder)
        val charInfo = currentLadder(charId)

        val newCharInfo = charInfo.copy(
          rating = ratingDelta,
          weeklyLosses = charInfo.weeklyLosses + 1,
          seasonLosses = charInfo.seasonLosses + 1)

        ladder.updated(charInfo.id, newCharInfo)
      }

    result
  }

  genLadder(ladderSize).foreach(println)
}
