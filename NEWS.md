## 1.1.6 2018-06-06
 * MODNOTIFY-5 Query validation
 * MODNOTIFY-25 GET /notify limit doesn't seem to be implemented
 * MODNOTIFY-26 GET /notify offset when outside the boundaries is being ignored
￼* MODNOTIFY-27 GET /notify - when invalid lang value provided - returns all rows and status 200
 * MODNOTIFY-29 PUT /notify/{non-existing-id} returns 500
 * MODNOTIFY-32 Use RMB 19.1.2
 * General code cleaning, using RMB's error helpers

## 1.1.5 2017-11-10
 * modnotify-15: Id is not returned with queries
 * modnotify-12: Upgrade to RMB 15
 * modnotify-14: Depend on mod-users 14.0 or 15.0, no direct mvn dependencies
 * modnotify-16: More strict validation, reject unknown fields

## 1.1.4 2017-10-18
 * modnotify-13: The lookup path is /_username/{username}, nothing with id.
 * modnotify-11: The user lookup returns a collection, not a user item

## 1.1.3 2017-10-12
 * Move the artifact into org.folio, not org.folio.rest.

## 1.1.2 2017-10-12
 * No changes, just re-releasing with a new version because of Jenkins problems

## 1.1.1 2017-10-11
 * MODNOTIFY-8: prevent PUT without recipient
 * MODNOTIFY-9: hack run.sh to work without dependencies

## 1.1.0 2017-10-11
 * MODNOTIFY-6: New endpoint to post notifications for userId.
   Dependency on mod-users.

## 1.0.0 2017-09-08
 * First real release
 * MODNOTIFY-2: Delete old notifications
 * MODNOTIFY-4: Use RMB 14.0.0. This is a BREAKING change, the "metaData"
   section has been renamed to "metadata". This affects the returned records,
   and the way things can be queried.

## 0.1.1 2017-08-29
 * Add forgotten Docker files

## 0.1.0 2017-08-29
 * Initial work
 * Basic CRUD interfaces in place

