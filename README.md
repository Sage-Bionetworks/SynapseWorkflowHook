## Synapse - Workflow Hook
Links one or more Synapse Evaluation queues to a workflow engine.  Each Evaluation queue is associated with a workflow template.  Each submission is a workflow job, an instance of the workflow template.  Upon submission to the Evaluation queue the Workflow Hook initiates and tracks the workflow job, sending progress notifications and uploading log files.


### Features:
- Services one or more Synapse submission queues.
- Enforces time quota (signaled by separate process).
- Reconciles running submissions listed in Synapse with those listed by the workflow engine.  Notifies admin' if there is a workflow job without a running submission listed in Synapse.
- Respects keyboard interrupt and ensures Workflow Hook stops at the end of a monitoring cycle.
- Implements submission cancellation.
- Tracks in Synapse when the job was started and ended (last updated).
- Notifies submitter about invalid submissions, notifies queue administrator about problems with the queue itself.
- Uploads logs to Synapse periodically and when done.
- Populates a status dashboard in Synapse with the following fields:
	- job status
	- start timestamp
	- last updated timestamp
	- location where log file is uploaded
	- failure reason (if workflow job failed to complete)
	- progress (0->100%), if provided by the Workflow engine
	
### Parameters (environment or properties; may put in .env file to use with Docker Compose):
- `DOCKER_ENGINE_URL` - address of the Docker engine.   Along with `DOCKER_CERT_PATH_HOST` this is needed since the Workflow Hook will manage containers
- `DOCKER_CERT_PATH_HOST` - credentials file allowing access to Docker engine
- `SYNAPSE_USERNAME` - Synapse credentials under which the Workflow Hook will run.  Must have access to evaluation queue(s) being serviced
- `SYNAPSE_PASSWORD` - password for `SYNAPSE_USERNAME`
- `WORKFLOW_TEMPDIR` - scratch folder on the host, mounted as /tempDir to the container running the WorkflowHook
- `WORKFLOW_OUTPUT_ROOT_ENTITY_ID` - root (Project or Folder) for uploaded doc's, like log files.  Hierarchy is root/submitterId/submissionId/files
- `EVALUATION_TEMPLATES` - json mapping evaluation IDs to URL for workflow template archive
- `NOTIFICATION_PRINCIPAL_ID` - (optional) Synapse ID of user or team to be notified of system issues.  If omitted then notification are sent to the Synapse account under which the workflow pipeline is run
- `SHARE_RESULTS_IMMEDIATELY` - (optional) if omitted or set to 'true', uploaded results are immediately accessible by submitter.  If false then a separate process must 'unlock' files.  This is useful when workflows run on sensitive data and administration needs to control the volume of results returned to the workflow submitter
- `DATA_UNLOCK_SYNAPSE_PRINCIPAL_ID` - (optional) Synapse ID of user authorized to share (unlock) workflow output files 
	(only required if `SHARE_RESULTS_IMMEDIATELY` is false)


### To use:

#### Create a project, submission queue, workflow template and dashboard
To run:

```
docker run --rm -it -e SYNAPSE_USERNAME=xxxxx -e SYNAPSE_PASSWORD=xxxxx \
-e WORKFLOW_TEMPLATE_URL=http://xxxxxx -e ROOT_TEMPLATE=xxxxx workflow-hook /set_up.sh
```

or

```
docker run --rm -it -e SYNAPSE_USERNAME=xxxxx -e SYNAPSE_PASSWORD=xxxxx \
-v /path/to/workflow/template:/template workflow-hook /set_up.sh
```
where `WORKFLOW_TEMPLATE_URL` is a link to a zip file and `ROOT_TEMPLATE` is a path within the zip where a workflow file can be found.


Will print out created Project ID and the value for the `EVALUATION_TEMPLATES` in the following step.

#### Start the workflow service

Create a .env file having the following environment variables:

- `DOCKER_ENGINE_URL`: address of the Docker engine, as defined above
- `DOCKER_CERT_PATH_HOST`: path to the Docker engine certificate, as defined above
- `WORKFLOW_TEMPDIR`: path to a scratch directory on the host machine
- `SYNAPSE_USERNAME`: Synapse credentials under which the Workflow Hook will run, as defined above
- `SYNAPSE_PASSWORD`: password for SYNAPSE_USERNAME
- `WORKFLOW_OUTPUT_ROOT_ENTITY_ID`: the ID of the project generated in the set-up step, above
- `EVALUATION_TEMPLATES`: the JSON object returned by the set up step
- `NOTIFICATION_PRINCIPAL_ID` - (optional) Synapse ID of user or team to be notified of system issues. 
- `SHARE_RESULTS_IMMEDIATELY` - (optional) if omitted or set to 'true', uploaded results are immediately accessible by submitter.
- `DATA_UNLOCK_SYNAPSE_PRINCIPAL_ID` - (optional) Synapse ID of user authorized to share (unlock) workflow output files 

Now run:

```
docker-compose up
```

#### Submit a job to the queue

```
docker run --rm -it -e SYNAPSE_USERNAME=xxxxx -e SYNAPSE_PASSWORD=xxxxx -e EVALUATION_ID=xxxxx \
-v /path/to/workflow/job:/workflowjob workflow-hook /submit.sh
```
where `EVALUATION_ID` is one of the keys in the `EVALUATION_TEMPLATES` map returned from the set-up step

### See results

In the Synapse web browser, visit the Project created in the first step.  You will see a dashboard of submissions.


#### Tear down
Stop the service:

```
docker-compose stop
```
Now, in Synapse, simply delete the root level project


