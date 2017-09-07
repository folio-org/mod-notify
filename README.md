# mod-notify

Copyright (C) 2017 The Open Library Foundation

This software is distributed under the terms of the Apache License,
Version 2.0. See the file "[LICENSE](LICENSE)" for more information.

## Introduction

Notifications to the users

This module manages notifications, simple messages sent to the users by some
system processes. For example, that a reserved book has become available, or
that a batch job has finished importing 1000 users, with 27 errors.

## Additional information

### Other documentation

Other [modules](http://dev.folio.org/source-code/#server-side) are described,
with further FOLIO Developer documentation at [dev.folio.org](http://dev.folio.org/)

### Issue tracker

See project [MODNOTIFY](https://issues.folio.org/browse/MODNOTIFY)
at the [FOLIO issue tracker](http://dev.folio.org/community/guide-issues).

### Quick start

Compile with `mvn clean install`

Run the local stand-alone instance:

```
java -jar target/mod-notify-fat.jar \
  -Dhttp.port=8085 embed_postgres=true
```

### API documentation

This module's [API documentation](http://dev.folio.org/doc/api/#mod-notify).

The local API docs are available, for example:
```
http://localhost:8085/apidocs/?raml=raml/notify.raml
http://localhost:8085/apidocs/?raml=raml/admin.raml
etc.
```

### Download and configuration

The built artifacts for this module are available.
See [configuration](http://dev.folio.org/doc/artifacts) for repository access,
and the [Docker image](https://hub.docker.com/r/folioorg/mod-notify/).

