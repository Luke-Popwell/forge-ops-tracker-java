package io.github.lukepopwell.tracker;

/**
 * How one delivery ended, as far as whoever holds the payload is concerned: gone out, never going
 * to go out, or worth another try. {@link Client}'s public methods still answer with a plain
 * boolean; {@link Client#outcome} is what tells the last two apart.
 */
enum DeliveryResult {
  /** Any 2xx. */
  DELIVERED,
  /**
   * A 4xx other than 408 and 429: the server will never take this payload (a bad key, a plan
   * without the feature, a body it can't parse or that is too large), so sending it again only
   * repeats the answer.
   */
  REJECTED,
  /** A network error, a timeout, a 5xx, a 408 or a 429: the same payload may well go through later. */
  FAILED;

  /**
   * How many flushes in a row may fail before a batch is dropped anyway, so an endpoint that stays
   * down can't make every later flush carry (and a full buffer keep refusing) more and more.
   */
  static final int MAX_FAILED_FLUSHES = 5;

  static DeliveryResult ofStatus(int status) {
    if (status >= 200 && status < 300) {
      return DELIVERED;
    }
    if (status >= 400 && status < 500 && status != 408 && status != 429) {
      return REJECTED;
    }
    return FAILED;
  }

  /** Why a batch that ended this way is let go, for the debug log. */
  String dropReason() {
    return this == REJECTED
        ? "the server rejected it"
        : "delivery failed " + MAX_FAILED_FLUSHES + " times in a row";
  }
}
