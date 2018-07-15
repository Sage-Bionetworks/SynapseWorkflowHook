#!/usr/bin/env cwl-runner
#
# download a submitted file from Synapse and return the downloaded file
#
cwlVersion: v1.0
class: CommandLineTool
baseCommand: python

inputs:
  - id: submissionId
    type: string
  - id: downloadLocation
    type: Directory

requirements:
  - class: InlineJavascriptRequirement
  - class: InitialWorkDirRequirement
    listing:
      - entryname: downloadSubmissionFile.py
        entry: |
          #!/usr/bin/env python
          import synapseclient
          import argparse
          import json
          parser = argparse.ArgumentParser()
          parser.add_argument("-s", "--submissionId", required=True, help="Submission ID")
          parser.add_argument("-f", "--fileDownloadFolder", required=True, help="File Download Location")
          parser.add_argument("-r", "--results", required=True, help="download results info")
          args = parser.parse_args()
          syn = synapseclient.Synapse(configPath="/root/.synapseConfig")
          syn.login()
          sub = syn.getSubmission(args.submissionId, downloadLocation=args.fileDownloadFolder)
          if sub.entity.entityType!='org.sagebionetworks.repo.model.FileEntity':
            raise Exception('Expected FileEntity type but found '+sub.entity.entityType)
          result = {'filePath':sub.filePath,'entityId':sub.entity.id,'entityVersion':sub.entity.versionNumber}
          with open(args.results, 'w') as o:
            o.write(json.dumps(result))
     
outputs:
  - id: filePath
    type: string
    outputBinding:
      glob: results.json
      loadContents: true
      outputEval: $(JSON.parse(self[0].contents)['filePath'])
  - id: entityId
    type: string
    outputBinding:
      glob: results.json
      loadContents: true
      outputEval: $(JSON.parse(self[0].contents)['entityId'])
  - id: entityVersion
    type: int
    outputBinding:
      glob: results.json
      loadContents: true
      outputEval: $(JSON.parse(self[0].contents)['entityVersion'])

arguments:
  - valueFrom: downloadSubmissionFile.py
  - valueFrom: $(inputs.submissionId)
    prefix: -s
  - valueFrom: $(inputs.downloadLocation.basename)
    prefix: -f
  - valueFrom: results.json
    prefix: -r


