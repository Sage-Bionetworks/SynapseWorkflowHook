#!/bin/bash
exec mvn exec:java -DentryPoint=org.sagebionetworks.WorkflowAdmin -Dexec.args="SET_UP /template"
