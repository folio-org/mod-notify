# mod-notify

Copyright (C) 2017-2019 The Open Library Foundation

This software is distributed under the terms of the Apache License,
Version 2.0. See the file "[LICENSE](LICENSE)" for more information.

## Introduction

Notifications to the users

This module manages notifications, simple messages sent to the users by some
system processes. For example, that a reserved book has become available, or
that a batch job has finished importing 1000 users, with 27 errors.

A simple notification looks something like this:
```
{
  "id" : "11111111-1111-1111-1111-111111111111",
  "recipientId" : "22222222-2222-2222-2222-222222222222",
  "text" : "Your item has been returned",
  "link" : "items/23456",
  "seen" : false,
  "metadata" : {
    "createdDate" : "2017-09-18 12:00:00",
    "createdByUserId" : "33333333-3333-3333-3333-333333333333"
  }
}
```

The important fields are the recipientId, the UUID of the user for whom this
notification is meant to, and the text.  There can also be a link to the thing
the notification is all about.

The most common operations on notifications are
*  POST a new notification to the system
*  PUT an update to a notification, typically to set the seen flag to true
*  GET notifications for a given user, typically with a filter for unseen ones

There is also an endpoint to post a notification with the userId in the URL.
This gets looked up in mod-users, and the recipient UUID is inserted in the
notification.

```POST /patron-notice``` allows to send notices to patrons.

All messages are sent to mod-sender with `POST /message-delivery-notice`.
mod-sender then decides which delivery channel should be used (e.g. email).

### Other documentation

Other [modules](https://dev.folio.org/source-code/#server-side) are described,
with further FOLIO Developer documentation at [dev.folio.org](https://dev.folio.org/)

[Patron notices documentation](https://wiki.folio.org/pages/viewpage.action?pageId=36575353).
### Issue tracker

See project [MODNOTIFY](https://issues.folio.org/browse/MODNOTIFY)
at the [FOLIO issue tracker](https://dev.folio.org/guidelines/issue-tracker/).

### Quick start

Compile with `mvn clean install`

Run the local stand-alone instance:

```
java -jar target/mod-notify-fat.jar \
  -Dhttp.port=8081 embed_postgres=true
```

See the `run.sh` script for some simple curl examples.

### ModuleDescriptor

See the built `target/ModuleDescriptor.json` for the interfaces that this module
requires and provides, the permissions, and the additional module metadata.

### API documentation

This module's [API documentation](https://dev.folio.org/reference/api/#mod-notify).

The local API docs are available, for example:
```
http://localhost:8081/apidocs/?raml=raml/notify.raml
http://localhost:8081/apidocs/?raml=raml/admin.raml
etc.
```

### Code analysis

[SonarQube analysis](https://sonarcloud.io/dashboard?id=org.folio%3Amod-notify).

### Download and configuration

The built artifacts for this module are available.
See [configuration](https://dev.folio.org/download/artifacts) for repository access,
and the [Docker image](https://hub.docker.com/r/folioorg/mod-notify/).
