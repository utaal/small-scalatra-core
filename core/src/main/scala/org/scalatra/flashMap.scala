package org.scalatra

import util.MutableMapWithIndifferentAccess
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import collection.JavaConverters._
import java.util.concurrent.{ConcurrentSkipListSet, ConcurrentHashMap}

/**
 * A FlashMap is the data structure used by [[org.scalatra.FlashMapSupport]]
 * to allow passing temporary values between sequential actions.
 *
 * FlashMap behaves like [[org.scalatra.util.MapWithIndifferentAccess]].  By
 * default, anything placed in the map is available to the current request and
 * next request, and is then discarded.
 *
 * @see FlashMapSupport
 */
class FlashMap extends MutableMapWithIndifferentAccess[Any] with Serializable {
  private[this] val m = new ConcurrentHashMap[String, Any]().asScala
  private[this] val flagged = new ConcurrentSkipListSet[String]().asScala

  /**
   * Removes an entry from the flash map.  It is no longer available for this
   * request or the next.
   */
  def -=(key: String) = {
    m -= key
    this
  }

  /**
   * Adds an entry to the flash map.  Clears the sweep flag for the key.
   */
  def +=(kv: (String, Any)) = {
    flagged -= kv._1
    m += kv
    this
  }

  /**
   * Creates a new iterator over the values of the flash map.  These are the
   * values that were added during the last request.
   */
  def iterator = new Iterator[(String, Any)] {
    private[this] val it = m.iterator

    def hasNext = it.hasNext

    def next = {
      val kv = it.next
      flagged += kv._1
      kv
    }
  }

  /**
   * Returns the value associated with a key and flags it to be swept.
   */
  def get(key: String) = {
    flagged += key
    m.get(key)
  }

  /**
   * Removes all flagged entries.
   */
  def sweep() {
    flagged foreach { key => m -= key }
  }

  /**
   * Clears all flags so no entries are removed on the next sweep.
   */
  def keep() {
    flagged.clear()
  }

  /**
   * Clears the flag for the specified key so its entry is not removed on the next sweep.
   */
  def keep(key: String) {
    flagged -= key
  }

  /**
   * Flags all current keys so the entire map is cleared on the next sweep.
   */
  def flag() {
    flagged ++= m.keys
  }

  /**
   * Sets a value for the current request only.  It will be removed before the next request unless explicitly kept.
   * Data put in this object is availble as usual:
   * {{{
   * flash.now("notice") = "logged in succesfully"
   * flash("notice") // "logged in succesfully"
   * }}}
   */
  object now {
    def update(key: String, value: Any) =  {
      flagged += key
      m += key -> value
    }
  }
}

object FlashMapSupport {
  val SessionKey = FlashMapSupport.getClass.getName+".flashMap"
  val LockKey = FlashMapSupport.getClass.getName+".lock"
  val FlashMapKey = "org.scalatra.FlashMap"
}

/**
 * Allows an action to set key-value pairs in a transient state that is accessible only to the next action and is expired immediately after that.
 * This is especially useful when using the POST-REDIRECT-GET pattern to trace the result of an operation.
 * {{{
 * post("/article/create") {
 *   // create session
 *   flash("notice") = "article created succesfully"
 *   redirect("/home")
 * }
 * get("/home") {
 *   // this will access the value set in previous action
 *   stuff_with(flash("notice"))
 * }
 * }}}
 * @see FlashMap
 */
trait FlashMapSupport extends Handler {
  this: ScalatraBase =>

  import FlashMapSupport._

  abstract override def handle(req: HttpServletRequest, res: HttpServletResponse) {
    withRequest(req) {
      val f = flash
      val isOutermost = !req.contains(LockKey)
      if (isOutermost) {
        req(LockKey) = "locked"
        if (sweepUnusedFlashEntries(req)) {
          f.flag()
        }
      }
      super.handle(req, res)
      /*
       * http://github.com/scalatra/scalatra/issues/41
       * http://github.com/scalatra/scalatra/issues/57
       *
       * Only the outermost FlashMapSupport sweeps it at the end.  
       * This deals with both nested filters and redirects to other servlets.
       */
      if (isOutermost) {
        f.sweep()
      }
      flashMapSetSession(f)
    }
  }
  
  /**
   * Override to implement custom session retriever, or sanity checks if session is still active
   * @param f
   */
  def flashMapSetSession(f: FlashMap) {
    try {
      // Save flashMap to Session after (a session could stop existing during a request, so catch exception)
      session(SessionKey) = f
    } catch {
      case e: Throwable =>
    }
  }

  private[this] def getFlash(req: HttpServletRequest): FlashMap =
    req.get(SessionKey).map(_.asInstanceOf[FlashMap]).getOrElse {
      val map = session.get(SessionKey).map {
        _.asInstanceOf[FlashMap]
      }.getOrElse(new FlashMap)

      req.setAttribute(SessionKey, map)
      map
    }

  /**
   * Returns the [[org.scalatra.FlashMap]] instance for the current request.
   */
  def flash(implicit request: HttpServletRequest): FlashMap = getFlash(request)

  def flash(key: String)(implicit request: HttpServletRequest) = getFlash(request)(key)

  /**
   * Determines whether unused flash entries should be swept.  The default is false.
   */
  protected def sweepUnusedFlashEntries(req: HttpServletRequest) = false
}
