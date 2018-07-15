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
          if __name__ == '__main__':
            parser = argparse.ArgumentParser()
            parser.add_argument("-f", "--infile", required=True, help="file to upload")
            parser.add_argument("-p", "--parentId", required=True, help="Synapse parent for file")
            parser.add_argument("-i", "--synid", required=True, help="Path to syn id")
            args = parser.parse_args()
            syn = synapseclient.Synapse(configPath="/root/.synapseConfig")
            syn.login()
            file=File(path=args.infile, parent=args.parentId)
            file = syn.upload(file)
            with open(args.downloadFilePath, 'w') as o:
              o.write(file.id)
     
outputs:
  - id: uploadedFileId
    type: string
    outputBinding:
      glob: synid.txt
      loadContents: true
      outputEval: $(self[0].contents)

arguments:
  - valueFrom: uploadFile.py
  - valueFrom: $(inputs.infile)
    prefix: -f
  - valueFrom: $(inputs.parentId)
    prefix: -p
  - valueFrom: synid.txt
    prefix: -i


