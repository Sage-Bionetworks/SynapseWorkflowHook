'''
Download submission file

Created on July 12, 2018

@author: bhoff
'''
import synapseclient
import argparse

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--submissionId", required=True, help="Submission ID")
    parser.add_argument("-f", "--fileDownloadLocation", required=True, help="File Download Location")
    args = parser.parse_args()

    syn = synapseclient.Synapse()
    # Client gets credentials from ~/.synapseConfig
    syn = syn.login()
    # TODO handle the case that the submission is a Docker commit, not a file
    syn.getSubmission(args.submissionId, downloadLocation=args.fileDownloadLocation)
