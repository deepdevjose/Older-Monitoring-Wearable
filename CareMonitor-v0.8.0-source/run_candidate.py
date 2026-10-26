"""Run the isolated assessment candidate on a separate local HTTPS port."""
import argparse
from pathlib import Path

import uvicorn
from care_monitor_server.candidate_app import create_account_candidate

ROOT = Path(__file__).resolve().parent
MODEL_SHA = '93f2ab696da2338bd059536cdf1f84b71eb98d4d7af290ce2e6c1515adceee7b'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--certfile', type=Path, required=True, help='An existing certificate file for this host')
    p.add_argument('--keyfile', type=Path, required=True, help='The matching existing private key; never share it')
    p.add_argument('--database', type=Path, default=ROOT / 'candidate-data/assessment.db')
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=8010)
    args = p.parse_args()
    if not args.certfile.is_file() or not args.keyfile.is_file():
        p.error('Certificate and private key must be existing files, not example placeholder paths')
    app = create_account_candidate(args.database,
        classification_manifest=ROOT / 'evidence/ml/manifest.json',
        classification_manifest_sha256=MODEL_SHA)
    uvicorn.run(app, host=args.host, port=args.port, ssl_certfile=str(args.certfile),
                ssl_keyfile=str(args.keyfile), proxy_headers=False, access_log=False)


if __name__ == '__main__':
    main()
