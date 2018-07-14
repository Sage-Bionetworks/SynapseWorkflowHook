#!/usr/bin/env cwl-runner
cwlVersion: v1.0
class: CommandLineTool
# need a no-op command 
baseCommand: ls
      
requirements:
  - class: InlineJavascriptRequirement
    expressionLib: 
    - |
        function parseParam(name, inp) {
          var j = JSON.parse(inp);
          if (name in j) {
            return j[name];
          } else {
            return null;
          }
        }
  - class: InitialWorkDirRequirement
    listing:
      - $(inputs.inputfile)
inputs:
  - id: inputfile
    type: File

outputs:
  - id: foo
    type: string?
    outputBinding:
      glob: $(inputs.inputfile.basename)
      loadContents: true
      outputEval: $(parseParam("foo",self[0].contents))
  - id: bar
    type:
      type: record
      fields:
        - name: a
          type: string
        - name: b
          type: int
    outputBinding:
      glob: $(inputs.inputfile.basename)
      loadContents: true
      outputEval: $(parseParam("bar",self[0].contents))
  - id: baz
    type: string?
    outputBinding:
      glob: $(inputs.inputfile.basename)
      loadContents: true
      outputEval: $(parseParam("baz",self[0].contents))

