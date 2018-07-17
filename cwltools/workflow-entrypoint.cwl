#!/usr/bin/env cwl-runner
cwlVersion: v1.0
class: Workflow

# the sole input for any Synapse-centric workflow is the submission id
inputs:
  - id: submissionId
    type: string  

# there are no output at the workflow engine level.  Everything is uploaded to Synapse
outputs: []

steps:
  downloadSubmission:
    run: downloadSubmissionFile.cwl
    in:
      - id: submissionId
        source: "#submissionId"
      - id: downloadLocation
        valueFrom: .
    out:
      - id: filePath
      - id: entity
      
  readWorkflowParameters:
    run:  job_file_reader_tool_yaml.cwl
    in:
      - id: inputfile
        source: "#downloadSubmission/filePath"
    out:
      - id: content
  
  coreWorkflow:
    run: sample-workflow.cwl
    in:
      - id: message
        source: "#readWorkflowParameters/content/message"
    out:
      - id stdout
  
  uploadResults:
    run: uploadToSynapse.cwl
    in:
      - id: infile
        source: "#coreWorkflow/stdout"
      - id: parentId
        valueFrom: syn12270235
      - id: usedEntity
        source: "#downloadSubmission/entity"
      - id: executedUrl
        valueFrom: "https://github.com/Sage-Bionetworks/SynapseWorkflowHook.git"
  
  annotateSubmissionWithoutOutput:
    run: 
    in:
    out:
 