#!/bin/bash

set -e

sim_root="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && cd .. && pwd )"
script=$sim_root/script
traces=$sim_root/traces

jupyter nbconvert \
    --ExecutePreprocessor.timeout=None \
    --execute $script/google-traces-parser_clusterdata-2011-2.ipynb

mv $script/google-simulator-traces/*.log $traces
mv $script/google-simulator-traces/job-distribution-traces/*.log $traces/job-distribution-traces
rm -rf $script/google-simulator-traces
rm -rf $script/spark-warehouse

