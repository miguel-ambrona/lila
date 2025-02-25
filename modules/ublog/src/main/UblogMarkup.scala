package lila.ublog

import java.util.regex.Matcher
import play.api.Mode
import scala.concurrent.duration.*

import lila.common.config
import lila.common.{ Bus, Chronometer, Markdown, MarkdownRender }
import lila.game.Game
import lila.hub.actorApi.lpv.GamePgnsFromText
import lila.memo.CacheApi

final class UblogMarkup(
    baseUrl: config.BaseUrl,
    assetBaseUrl: config.AssetBaseUrl,
    cacheApi: CacheApi,
    netDomain: config.NetDomain
)(using ec: scala.concurrent.ExecutionContext, scheduler: akka.actor.Scheduler, mode: Mode):

  import UblogMarkup.*

  private val pgnCache = cacheApi.notLoadingSync[GameId, String](256, "ublogMarkup.pgn") {
    _.expireAfterWrite(1 second).build()
  }

  private val renderer = new MarkdownRender(
    autoLink = true,
    list = true,
    strikeThrough = true,
    header = true,
    blockQuote = true,
    code = true,
    table = true,
    gameExpand = MarkdownRender.GameExpand(netDomain, pgnCache.getIfPresent).some
  )

  def apply(post: UblogPost) = cache.get((post.id, post.markdown)).map { html =>
    scalatags.Text.all.raw(html.value)
  }

  private val cache = cacheApi[(UblogPostId, Markdown), Html](2048, "ublog.markup") {
    _.maximumSize(2048)
      .expireAfterWrite(if (mode == Mode.Prod) 15 minutes else 1 second)
      .buildAsyncFuture { case (id, markdown) =>
        Bus.ask("lpv")(GamePgnsFromText(markdown.value, _)) andThen { case scala.util.Success(pgns) =>
          pgnCache.putAll(pgns)
        } inject process(id)(markdown)
      }
  }

  private def process(id: UblogPostId): Markdown => Html = replaceGameGifs.apply andThen
    unescapeAtUsername.apply andThen
    renderer(s"ublog:${id}") andThen
    imageParagraph andThen
    unescapeUnderscoreInLinks.apply

  // replace game GIFs URLs with actual game URLs that can be embedded
  private object replaceGameGifs:
    private val regex = (assetBaseUrl.value + """/game/export/gif(/white|/black|)/(\w{8})\.gif""").r
    val apply         = (markdown: Markdown) => markdown.map(regex.replaceAllIn(_, baseUrl.value + "/$2$1"))

  // put images into a container for styling
  private def imageParagraph(markup: Html) =
    markup.map(_.replace("""<p><img src=""", """<p class="img-container"><img src="""))

private[ublog] object UblogMarkup:

  private def unescape(txt: String) = txt.replace("""\_""", "_")

  // https://github.com/lichess-org/lila/issues/9767
  // toastui editor escapes `_` as `\_` and it breaks autolinks
  object unescapeUnderscoreInLinks:
    private val hrefRegex    = """href="([^"]+)"""".r
    private val contentRegex = """>([^<]+)</a>""".r
    def apply(markup: Html) = Html {
      contentRegex.replaceAllIn(
        hrefRegex
          .replaceAllIn(markup.value, m => s"""href="${Matcher.quoteReplacement(unescape(m group 1))}""""),
        m => s""">${Matcher.quoteReplacement(unescape(m group 1))}</a>"""
      )
    }

  // toastui editor escapes `_` as `\_` and it breaks @username
  object unescapeAtUsername:
    // Same as `atUsernameRegex` in `RawHtmlTest.scala` but it also matches the '\' character.
    // Can't end with '\', which would be escaping something after the username, like '\)'
    private val atUsernameRegexEscaped = """@(?<![\w@#/]@)([\w\\-]{1,29}\w)(?![@\w-]|\.\w)""".r
    def apply(markdown: Markdown) =
      markdown.map(atUsernameRegexEscaped.replaceAllIn(_, a => s"@${unescape(a group 1)}"))
