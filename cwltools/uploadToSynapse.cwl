#!/usr/bin/env cwl-runner
#
# upload a file to Synapse and return the ID
#
cwlVersion: v1.0
class: CommandLineTool
baseCommand: python

inputs:
  - id: infile
    type: File
  - id: parentId
    type: string

requirements:
  - class: InlineJavascriptRequirement
  - class: InitialWorkDirRequirement
    listing:
      - entryname: uploadFile.py
        entry: |
          #!/usr/bin/env python
          import synapseclient
          import argparse
          import json
          if __name__ == '__main__':
            parser = argparse.ArgumentParser()
            parser.add_argument("-f", "--infile", required=True, help="file to upload")
            parser.add_argument("-p", "--parentId", required=True, help="Synapse parent for file")
            parser.add_argument("-r", "--results", required=True, help="Results of file upload")
            args = parser.parse_args()
            syn = synapseclient.Synapse(configPath="/root/.synapseConfig")
            syn.login()
            file=synapseclient.File(path=args.infile, parent=args.parentId)
            file = syn.store(file)
            results = {'uploadedFileId':file.id,'uploadedFileVersion':file.versionNumber}
            with open(args.results, 'w') as o:
              o.write(json.dumps(results))
     
outputs:
  - id: uploadedFileId
    type: string
    outputBinding:
      glob: results.json
      loadContents: true
      outputEval: $(JSON.parse(self[0].contents)['uploadedFileId'])
  - id: uploadedFileVersion
    type: int
    outputBinding:
      glob: results.json
      loadContents: true
      outputEval: $(JSON.parse(self[0].contents)['uploadedFileVersion'])

arguments:
  - valueFrom: uploadFile.py
  - valueFrom: $(inputs.infile)
    prefix: -f
  - valueFrom: $(inputs.parentId)
    prefix: -p
  - valueFrom: results.json
    prefix: -r


