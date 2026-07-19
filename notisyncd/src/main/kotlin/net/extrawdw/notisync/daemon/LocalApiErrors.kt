package net.extrawdw.notisync.daemon

/** Local request did not identify an allowed same-UID caller or application operation. */
class LocalAuthorizationException(message: String) : RuntimeException(message)

/** Local request conflicts with current application/inbox state. */
class LocalConflictException(message: String) : RuntimeException(message)

/** A complete inbound fan-out could not fit in the bounded in-memory application inboxes. */
class LocalEventQueueFullException(message: String) : RuntimeException(message)
