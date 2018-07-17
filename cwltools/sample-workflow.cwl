#!/usr/bin/env cwl-runner
#
#  This sample workflow simply echos its input
#
cwlVersion: v1.0
class: CommandLineTool
baseCommand: echo
inputs:
  message:
    type: string
    inputBinding:
      position: 1
      
      
stdout: stdout.txt

outputs:
  - id: stdout
    type: File
    outputBinding:
      glob: stdout.txt
