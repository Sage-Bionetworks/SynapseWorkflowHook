#!/bin/bash
exec mvn exec:java -DentryPoint=org.sagebionetworks.WorkflowAdmin \
-Dexec.args="SUBMIT /workflowjob ${EVALUATION_ID}"
