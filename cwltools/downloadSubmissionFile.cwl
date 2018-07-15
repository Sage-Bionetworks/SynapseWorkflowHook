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
          if __name__ == '__main__':
            parser = argparse.ArgumentParser()
            parser.add_argument("-s", "--submissionId", required=True, help="Submission ID")
            parser.add_argument("-f", "--fileDownloadFolder", required=True, help="File Download Location")
            parser.add_argument("-p", "--downloadFilePath", required=True, help="Path to downloaded file")
            args = parser.parse_args()
            syn = synapseclient.Synapse(configPath="/root/.synapseConfig")
            syn.login()
            sub = syn.getSubmission(args.submissionId, downloadLocation=args.fileDownloadFolder)
            if sub.entity.entityType!='org.sagebionetworks.repo.model.FileEntity':
              raise Exception('Expected FileEntity type but found '+sub.entity.entityType)
            with open(args.downloadFilePath, 'w') as o:
              o.write(sub.filePath)
     
outputs:
  - id: submissionFile
    type: string
    outputBinding:
      glob: path.txt
      loadContents: true
      outputEval: $(self[0].contents)

arguments:
  - valueFrom: downloadSubmissionFile.py
  - valueFrom: $(inputs.submissionId)
    prefix: -s
  - valueFrom: $(inputs.downloadLocation.basename)
    prefix: -f
  - valueFrom: path.txt
    prefix: -p


