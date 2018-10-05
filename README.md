## Synapse Workflow Hook
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
	
### To use:

#### Create a project, submission queue, workflow template and dashboard
To run:

```
docker run --rm -it -e SYNAPSE_USERNAME=xxxxx -e SYNAPSE_PASSWORD=xxxxx \
-e WORKFLOW_TEMPLATE_URL=http://xxxxxx -e ROOT_TEMPLATE=xxxxx sagebionetworks/synapseworkflowhook /set_up.sh
```

where `WORKFLOW_TEMPLATE_URL` is a link to a zip file and `ROOT_TEMPLATE` is a path within the zip where a workflow file can be found.  To use a workflow in Dockstore:

```
docker run --rm -it -e SYNAPSE_USERNAME=xxxxx -e SYNAPSE_PASSWORD=xxxxx \
-e WORKFLOW_TEMPLATE_URL=https://dockstore.org:8443/api/ga4gh/v2/tools/{id}/versions/{version_id}/CWL \
-e ROOT_TEMPLATE=xxxxx sagebionetworks/synapseworkflowhook /set_up.sh
```
TODO:  Automatically lookup ROOT_TEMPLATE in Dockstore

Will print out created Project ID and the value for the `EVALUATION_TEMPLATES` in the following step.

#### Start the workflow service

Set the following as environment variables or properties.  To use with Docker Compose you may place them in and .env file:
- `DOCKER_ENGINE_URL` - address of the Docker engine.   Along with `DOCKER_CERT_PATH_HOST` this is needed since the Workflow Hook will manage containers.  Examples:

```
DOCKER_ENGINE_URL=unix:///var/run/docker.sock
```
or

```
DOCKER_ENGINE_URL=tcp://192.168.0.1:2376
```
- `DOCKER_CERT_PATH_HOST` - (optional) path to credentials files allowing networked access to Docker engine.  Required if connecting over the network (`DOCKER_ENGINE_URL` starts with `http`, `https` or `tcp`, but not with `unix`).  Example:

```
DOCKER_CERT_PATH_HOST=/my/home/dir/.docker/machine/certs
```
When using `DOCKER_CERT_PATH_HOST` you must also add the following under `volumes:` in `docker-compose.yaml`:

```
    - ${DOCKER_CERT_PATH_HOST}:/certs:ro
```
- `WORKFLOW_TEMPDIR` - path to a scratch folder on the host machine, mounted as /tempDir to the container running the WorkflowHook
- `SYNAPSE_USERNAME` - Synapse credentials under which the Workflow Hook will run.  Must have access to evaluation queue(s) being serviced
- `SYNAPSE_PASSWORD` - password for `SYNAPSE_USERNAME`
- `WORKFLOW_OUTPUT_ROOT_ENTITY_ID` - root (Project or Folder) for uploaded doc's, like log files.  Hierarchy is root/submitterId/submissionId/files. May be the ID of the project generated in the set-up step, above.
- `EVALUATION_TEMPLATES` - JSON mapping evaluation ID(s) to URL(s) for workflow template archive.  Returned by the set up step, above.  Example:

```
{"9614045":"syn16799953"}
```
- `TOIL_CLI_OPTIONS` - (optional) Space separated list of options.  See https://toil.readthedocs.io/en/3.15.0/running/cliOptions.html.  Example:

```
TOIL_CLI_OPTIONS=--defaultMemory 100M --retryCount 0 --defaultDisk 1000000
```
- `NOTIFICATION_PRINCIPAL_ID` - (optional) Synapse ID of user or team to be notified of system issues.  If omitted then notification are sent to the Synapse account under which the workflow pipeline is run.
- `SHARE_RESULTS_IMMEDIATELY` - (optional) if omitted or set to 'true', uploaded results are immediately accessible by submitter.  If false then a separate process must 'unlock' files.  This is useful when workflows run on sensitive data and administration needs to control the volume of results returned to the workflow submitter.
- `DATA_UNLOCK_SYNAPSE_PRINCIPAL_ID` - (optional) Synapse ID of user authorized to share (unlock) workflow output files 
	(only required if `SHARE_RESULTS_IMMEDIATELY` is false).
- `WORKFLOW_ENGINE_DOCKER_IMAGE` - (optional) defaults to sagebionetworks/synapseworkflowhook-toil, produced from [this Dockerfile](Dockerfile.Toil).  When overriding the default, you must ensure that the existing dependencies are preserved.  One way to do this is to start your own Dockerfile with

```
FROM sagebionetworks/synapseworkflowhook-toil
```
and then to add additional dependencies.

Now run:

```
docker-compose up
```

#### Submit a job to the queue

```
docker run --rm -it -e SYNAPSE_USERNAME=xxxxx -e SYNAPSE_PASSWORD=xxxxx -e EVALUATION_ID=xxxxx \
-v /path/to/workflow/job:/workflowjob sagebionetworks/synapseworkflowhook /submit.sh
```
where `EVALUATION_ID` is one of the keys in the `EVALUATION_TEMPLATES` map returned from the set-up step

### See results

In the Synapse web browser, visit the Project created in the first step.  You will see a dashboard of submissions.


#### Tear down
Stop the service:

```
docker-compose down
```
Now, in Synapse, simply delete the root level project


### Workflow creation guidelines

See [this example](https://github.com/Sage-Bionetworks/SynapseWorkflowExample) for a working example of a Synapse-linked workflow.  It includes reusable steps for downloading submissions and files, uploading files and annotating submissions.    Some notes:


- The workflow inputs are non-negotiable and must be as shown in the [sample workflow entry point](https://github.com/Sage-Bionetworks/SynapseWorkflowExample/blob/master/workflow-entrypoint.cwl).


- If the submission is a .cwl input file then it can be download by [this script](https://github.com/Sage-Bionetworks/SynapseWorkflowExample/blob/master/downloadSubmissionFile.cwl) and parsed by a step customized from [this example](https://github.com/Sage-Bionetworks/SynapseWorkflowExample/blob/master/job_file_reader_tool_yaml_sample.cwl).


- The workflow should not change the 'status' field of the submission status, which is reserved for the use of the Workflow Hook.


- The workflow must have no output.  Any results should be written to Synapse along the way, e.g., as shown in in [this example](https://github.com/Sage-Bionetworks/SynapseWorkflowExample/blob/master/uploadToSynapse.cwl).

### Other details

#### Uploading results

The workflow hook uses this folder hierarchy for uploading results:

```
< WORKFLOW_OUTPUT_ROOT_ENTITY_ID> / <SUBMITTER_ID> / <SUBMISSION_ID> / 
```
and
 

```
< WORKFLOW_OUTPUT_ROOT_ENTITY_ID> / <SUBMITTER_ID>_LOCKED / <SUBMISSION_ID> / 
```
where 

- `<WORKFLOW_OUTPUT_ROOT_ENTITY_ID>` is a parameter passed to the hook at startup;

- `<SUBMITTER_ID>` is the user or team responsible for the submission;

- `<SUBMISSION_ID>` is the ID of the submission;

When `SHARE_RESULTS_IMMEDIATELY` is omitted or set to `true` then logs are uploaded into the unlocked folder.  When  `SHARE_RESULTS_IMMEDIATELY` is set to `false` then logs are uploaded into the locked folder.  To share the log file (or anything else uploaded to the `_LOCKED` folder) with the submitter, a process separate from the workflow should *move* the item(s) to the unlocked folder, rather than by creating an ACL on the lowest level folder.  Such a process can run under a separate Synapse account, if desired.  If so, set `DATA_UNLOCK_SYNAPSE_PRINCIPAL_ID` to be the Synapse principal ID of the account used to run that process.

The workflow is passed the IDs of both the locked and unlocked submission folders so it can choose whether the submitter can see the results it uploads by choosing which folder to upload to.

#### Timing out

The workflow hook checks each submission for an integer (long) annotation named `org.sagebionetworks.SynapseWorkflowHook.TimeRemaining`.  If the value is present and not greater than zero then the submission will be stopped and a "timed out" notification sent.  If the annotation is not present then no action will be taken.  Through this mechanism a custom application can determine which submissions have exceeded their alloted time and stop them.   Such an application is communicating with the workflow hook via the submissions' annotations.  This architecture allows each submission queue administrator to customize the time-out logic rather than having some particular algorithm hard-coded into the workflow hook.

