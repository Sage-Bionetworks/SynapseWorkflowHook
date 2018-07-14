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
          var patt = new RegExp(name+":[\\s]*(.*)");
          var result = inp.match(patt);
          if (result==null) return null;
          return result[1];
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
    type: string?
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

