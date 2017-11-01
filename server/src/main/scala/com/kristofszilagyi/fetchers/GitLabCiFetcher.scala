package com.kristofszilagyi.fetchers

import java.time.Instant

import akka.typed.Behavior
import akka.typed.scaladsl.Actor
import com.kristofszilagyi.controllers.{GitLabCiAccessToken, JobNameOnGitLab}
import com.kristofszilagyi.fetchers.GitLabCiFetcher.GitLabCiJson.PartialJobsInfo
import com.kristofszilagyi.shared._
import com.netaporter.uri.Uri
import com.netaporter.uri.dsl._
import io.circe.generic.JsonCodec
import play.api.libs.ws.{WSClient, WSRequest}
import slogging.LazyLogging
import GitLabCiFetcher._
import TypeSafeEqualsOps._
import com.kristofszilagyi.fetchers.JenkinsFetcher.Fetch
import com.kristofszilagyi.utils.Utopia
import com.kristofszilagyi.utils.Utopia.RichFuture

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


final case class GitLabCiJob(common: Job, maybeAccessToken: Option[GitLabCiAccessToken], jobNameOnGitLab: JobNameOnGitLab) {
  def jobInfo: RawUrl = common.jobInfo
  def authenticatedRestRequest(ws: WSClient): WSRequest = {
    val headers = maybeAccessToken.map { accessToken =>
      "PRIVATE-TOKEN" -> accessToken.s
    }.toList
    ws.url(jobInfo.rawString).withHttpHeaders(headers: _*)
  }
}

object GitLabCiFetcher {

  @SuppressWarnings(Array(Wart.Public))
  object GitLabCiJson {
    import io.circe.java8.time._

    @JsonCodec
    final case class PartialJobInfo(id: Int, name: String, created_at: Instant,
                                    started_at: Option[Instant], finished_at: Option[Instant],
                                    status: GitLabCiBuildStatus) {

      def buildNumber: BuildNumber = BuildNumber(id)
    }
    type PartialJobsInfo = Seq[PartialJobInfo]
  }

}
//todo probably this should be a future....
final class GitLabCiFetcher(ws: WSClient,
                      jobsToFetch: Seq[GitLabCiJob])(implicit ec: ExecutionContext) extends LazyLogging with Fetcher {
  def behaviour: Actor.Immutable[Fetch] = Actor.immutable[Fetch] { (_, msg) =>
    msg match {
      case Fetch(replyTo) =>
        val results = jobsToFetch.map { job =>
          val allbuildsForProjectFut = job.authenticatedRestRequest(ws).get.map(safeRead[PartialJobsInfo](job.jobInfo, _))
          val buildsWithRightNameFut = allbuildsForProjectFut.map{ allBuildsForProject =>
            allBuildsForProject.map(_.filter(_.name ==== job.jobNameOnGitLab.s))
          }
          val buildsFut = buildsWithRightNameFut.map{ buildsWithRightName =>
            buildsWithRightName.map(_.flatMap { build =>
              build.status match {
                case status: DisplayableGitLabCiStatus =>
                  build.started_at.map(start => Right(BuildInfo(status.toBuildStatus, buildStart = start,
                    maybeBuildFinish = build.finished_at, build.buildNumber))).toList
                case _ => None.toList
              }
            })
          }
          buildsFut.lift noThrowingMap {
            case Failure(ex) => JobDetails(job.common, Left(ResponseError.failedToConnect(job.jobInfo, ex)))
            case Success(builds) => JobDetails(job.common, builds)
          }
        }
        Utopia.sequence(results).onComplete { result =>
          replyTo ! FetcherResult(result)
        }
        Actor.same
    }
  }

  def name: String = "gitLabCi"
}