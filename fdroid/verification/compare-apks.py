#!/usr/bin/env python3
"""Compare APK payloads and require a valid copied signature for a signed reference."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import struct
import sys
import zipfile

from fdroidserver import apksigcopier


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def inventory(filename):
    with zipfile.ZipFile(filename) as apk:
        return {entry.filename: sha256(apk.read(entry)) for entry in apk.infolist()}


def zip_details(filename):
    result = {}
    with zipfile.ZipFile(filename) as apk, filename.open('rb') as raw:
        order = apk.namelist()
        for entry in apk.infolist():
            raw.seek(entry.header_offset)
            header = struct.unpack('<IHHHHHIIIHH', raw.read(30))
            raw.seek(header[-2], 1)
            local_extra = raw.read(header[-1])
            result[entry.filename] = {
                'compressedSha256': sha256(raw.read(entry.compress_size)),
                'compressedSize': entry.compress_size,
                'method': entry.compress_type, 'flags': entry.flag_bits,
                'timestamp': entry.date_time, 'createSystem': entry.create_system,
                'externalAttributes': entry.external_attr, 'extra': entry.extra.hex(),
                'localExtra': local_extra.hex(),
            }
    return result, order


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('report', type=Path)
    parser.add_argument('--signed-reference', action='store_true')
    args = parser.parse_args()
    left, right = inventory(args.reference), inventory(args.candidate)
    left_zip, left_order = zip_details(args.reference)
    right_zip, right_order = zip_details(args.candidate)
    result = {
        'reference': str(args.reference), 'candidate': str(args.candidate),
        'referenceSha256': sha256(args.reference.read_bytes()),
        'candidateSha256': sha256(args.candidate.read_bytes()),
        'onlyInReference': sorted(left.keys() - right.keys()),
        'onlyInCandidate': sorted(right.keys() - left.keys()),
        'changedEntries': sorted(name for name in left.keys() & right.keys() if left[name] != right[name]),
        'compressedStreamDifferences': sorted(name for name in left.keys() & right.keys()
                                             if left_zip[name]['compressedSha256'] != right_zip[name]['compressedSha256']),
        'zipMetadataDifferences': {
            name: {field: [left_zip[name][field], right_zip[name][field]]
                   for field in left_zip[name] if field != 'compressedSha256' and left_zip[name][field] != right_zip[name][field]}
            for name in sorted(left.keys() & right.keys()) if left_zip[name] != right_zip[name]
        },
        'sameEntryOrder': left_order == right_order,
    }
    if args.signed_reference:
        patched = args.report.with_suffix('.signature-copied.apk')
        try:
            subprocess.run(['apksigner', 'verify', str(args.reference)], check=True)
            apksigcopier.do_copy(str(args.reference), str(args.candidate), str(patched))
            subprocess.run(['apksigner', 'verify', str(patched)], check=True)
            result['signatureCopyVerified'] = True
            result['signatureCopiedSha256'] = sha256(patched.read_bytes())
        except (subprocess.CalledProcessError, apksigcopier.APKSigCopierError) as error:
            result['signatureCopyVerified'] = False
            result['error'] = str(error)
        passed = result['signatureCopyVerified']
    else:
        passed = result['referenceSha256'] == result['candidateSha256']
    result['passed'] = passed
    args.report.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
