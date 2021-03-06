#!/bin/bash

# This script runs WordCount example locally in a few different ways.
# Specifically, all combinations of:
#  a) using mvn exec, or java -cp with a bundled jar file;
#  b) input filename with no directory component, with a relative directory, or
#     with an absolute directory; AND
#  c) input filename containing wildcards or not.
#
# The one optional parameter is a path from the directory containing the script
# to the directory containing the top-level (parent) pom.xml.  If no parameter
# is provided, the script assumes that directory is equal to the directory
# containing the script itself.
#
# The exit-code of the script indicates success or a failure.

set -e
set -o pipefail

MYDIR=$(dirname $0) || exit 2
cd $MYDIR

TOPDIR="."
if [[ $# -gt 0 ]]
then
  TOPDIR="$1"
fi

PASS=1
JAR_FILE=$TOPDIR/examples/target/google-cloud-dataflow-java-examples-all-bundled-manual_build.jar

function check_result_hash {
  local name=$1
  local outfile_prefix=$2
  local expected=$3

  local actual=$(LC_ALL=C sort $outfile_prefix-* | md5sum | awk '{print $1}' \
    || LC_ALL=C sort $outfile_prefix-* | md5 -q) || exit 2  # OSX
  if [[ "$actual" != "$expected" ]]
  then
    echo "FAIL $name: Output hash mismatch.  Got $actual, expected $expected."
    PASS=""
    echo "head hexdump of actual:"
    head $outfile_prefix-* | hexdump -c
  else
    echo "pass $name"
    # Output files are left behind in /tmp
  fi
}

function get_outfile_prefix {
  local name=$1
  # NOTE: mktemp on OSX doesn't support --tmpdir
  mktemp -u "/tmp/$name.out.XXXXXXXXXX"
}

function run_via_mvn {
  local name=$1
  local input=$2
  local expected_hash=$3

  local outfile_prefix="$(get_outfile_prefix "$name")" || exit 2
  local cmd='mvn exec:java -f '"$TOPDIR"'/pom.xml -pl examples \
    -Dexec.mainClass=com.google.cloud.dataflow.examples.WordCount \
    -Dexec.args="--runner=DirectPipelineRunner --inputFile='"$input"' --output='"$outfile_prefix"'"'
  echo "$name: Running $cmd" >&2
  sh -c "$cmd"
  check_result_hash "$name" "$outfile_prefix" "$expected_hash"
}

function run_bundled {
  local name=$1
  local input=$2
  local expected_hash=$3

  local outfile_prefix="$(get_outfile_prefix "$name")" || exit 2
  local cmd='java -cp '"$JAR_FILE"' \
    com.google.cloud.dataflow.examples.WordCount \
    --runner=DirectPipelineRunner \
    --inputFile='"'$input'"' \
    --output='"$outfile_prefix"
  echo "$name: Running $cmd" >&2
  sh -c "$cmd"
  check_result_hash "$name" "$outfile_prefix" "$expected_hash"
}

function run_all_ways {
  local name=$1
  local input=$2
  local expected_hash=$3

  run_via_mvn ${name}a "$input" $expected_hash
  check_for_jar_file
  run_bundled ${name}b "$input" $expected_hash
}

function check_for_jar_file {
  if [[ ! -f $JAR_FILE ]]
  then
    echo "Jar file $JAR_FILE not created" >&2
    exit 2
  fi
}

run_all_ways wordcount1 "LICENSE" c5350a5ad4bb51e3e018612b4b044097
run_all_ways wordcount2 "./LICENSE" c5350a5ad4bb51e3e018612b4b044097
run_all_ways wordcount3 "$PWD/LICENSE" c5350a5ad4bb51e3e018612b4b044097
run_all_ways wordcount4 "L*N?E*" c5350a5ad4bb51e3e018612b4b044097
run_all_ways wordcount5 "./LICE*N?E" c5350a5ad4bb51e3e018612b4b044097
run_all_ways wordcount6 "$PWD/*LIC?NSE" c5350a5ad4bb51e3e018612b4b044097

if [[ ! "$PASS" ]]
then
  echo "One or more tests FAILED."
  exit 1
fi
echo "All tests PASS"
