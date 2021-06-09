#!/bin/bash
# Simple test script for mod-notify
# Loads mod-notify, but does not bother with the whole auth stack.
# That means userId lookup will not work.
# Those tests are included in mod-notes


# Parameters
OKAPIPORT=9130
OKAPIURL="http://localhost:$OKAPIPORT"
CURL="curl -w\n -D - "

# Check we have the fat jar
if [ ! -f target/mod-notify-fat.jar ]
then
  echo No fat jar found, no point in trying to run
  exit 1
fi

# Start Okapi (in dev mode, no database)
OKAPIPATH="../okapi/okapi-core/target/okapi-core-fat.jar"
java -Dport=$OKAPIPORT -jar $OKAPIPATH dev > okapi.log 2>&1 &
PID=$!
echo Started okapi on port $OKAPIPORT. PID=$PID
sleep 1 # give it time to start
echo

# Load mod-notify
echo "Loading mod-notify"
# Dirty trick: Remove the dependency on mod-users.
echo `cat target/ModuleDescriptor.json` |
  sed 's/"requires": \[[^]]*\]/"requires":[]/' > /tmp/md.json
$CURL -X POST -d@/tmp/md.json $OKAPIURL/_/proxy/modules
echo

echo "Deploying it"
$CURL -X POST \
   -d@target/DeploymentDescriptor.json \
   $OKAPIURL/_/discovery/modules
echo

# Test tenant
echo "Creating test tenant"
cat > /tmp/okapi.tenant.json <<END
{
  "id": "testlib",
  "name": "Test Library",
  "description": "Our Own Test Library"
}
END
$CURL -d@/tmp/okapi.tenant.json $OKAPIURL/_/proxy/tenants
echo
echo "Enabling it (without specifying the version)"
$CURL -X POST \
   -d'{"id":"mod-notify"}' \
   $OKAPIURL/_/proxy/tenants/testlib/modules
echo
sleep 1


# Various tests
echo Test 1: get empty list
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify
echo


echo Test 2: Post one
$CURL \
  -H "Content-type:application/json" \
  -H "X-Okapi-Tenant:testlib" \
  -H "X-Okapi-User-Id: 55555555-5555-5555-5555-555555555555" \
  -X POST -d '{"link":"users/56789","recipientId":"77777777-7777-7777-7777-777777777777","text":"hello there"}' \
  $OKAPIURL/notify

echo Test 3: get a list with the new one
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify
echo

echo Test 4: Post another one
$CURL \
  -H "Content-type:application/json" \
  -H "X-Okapi-Tenant:testlib" \
  -H "X-Okapi-User-Id: 66666666-6666-6666-6666-666666666666" \
  -X POST -d '{"id":"11111111-1111-1111-1111-111111111111", "link":"items/23456","recipientId":"77777777-7777-7777-7777-777777777777","text":"hello thing", "seen":true}' \
  $OKAPIURL/notify

echo Test 5: get a list with both
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify
echo

echo Test 6: query the user note
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=link=users
echo

echo Test 7: query both
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=text=hello
echo

echo Test 8: query both
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query='link=*56*'
echo

echo Test 9: Bad queries. Should all fail with 422 - some may succeed with no hits
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=BADQUERY
echo
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=BADFIELD=foo
echo
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=metadata.BADFIELD=foo
echo

echo Test 10: limit
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?limit=1
echo

echo Test 11: sort
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=text=hello+sortby+link%2Fsort.ascending
echo
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify?query=text=hello+sortby+link%2Fsort.descending
echo

echo Test 12: Get by id
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify/11111111-1111-1111-1111-111111111111
echo

echo Test 13: Put
$CURL \
  -H "Content-type:application/json" \
  -H "X-Okapi-Tenant:testlib" \
  -H "X-Okapi-User-Id: 77777777-7777-7777-7777-777777777777" \
  -X PUT -d '{"id":"11111111-1111-1111-1111-111111111111", "link":"items/23456","seen":true,
    "text":"hello AGAIN, thing", "recipientId":"77777777-7777-7777-7777-777777777777"}' \
  $OKAPIURL/notify/11111111-1111-1111-1111-111111111111
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify/11111111-1111-1111-1111-111111111111
echo


#echo Test 14: Delete
#$CURL -X DELETE -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify/11111111-1111-1111-1111-111111111111
#echo
#$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify/11111111-1111-1111-1111-111111111111
#echo

echo Test 14: Delete self - notfound
$CURL \
  -H "X-Okapi-Tenant:testlib" \
  -H "X-Okapi-User-Id:77777777-7777-7777-7777-777777777777" \
  -X DELETE\
  $OKAPIURL/notify/user/_self?olderthan=1999-12-31
echo

echo Test 15: Delete self
$CURL \
  -H "X-Okapi-Tenant:testlib" \
  -H "X-Okapi-User-Id:77777777-7777-7777-7777-777777777777" \
  -X DELETE\
  $OKAPIURL/notify/user/_self?olderthan=2099-12-31
echo

echo "Test 16: get a list with the remaining (unseen) note"
$CURL -H "X-Okapi-Tenant:testlib" $OKAPIURL/notify
echo


# Let it run
echo
echo "Hit enter to close"
read

# Clean up
echo "Cleaning up: Killing Okapi $PID"
kill $PID
ps | grep java && ( echo ... ; sleep 1  )
ps | grep java && ( echo ... ; sleep 1  )
ps | grep java && ( echo ... ; sleep 1  )
ps | grep java && ( echo ... ; sleep 1  )
ps | grep java && ( echo ... ; sleep 1  )
rm -rf /tmp/postgresql-embed*
ps | grep java && echo "OOPS - Still some processes running"
echo bye

