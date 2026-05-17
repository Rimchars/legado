#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


GITHUB_API = "https://api.github.com/repos/{owner}/{repo}"
GITEE_API = "https://gitee.com/api/v5/repos/{owner}/{repo}"


def request_json(url, method="GET", data=None, headers=None):
    body = None
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    if data is not None:
        body = urllib.parse.urlencode(data).encode("utf-8")
        req_headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read().decode("utf-8")
        return json.loads(raw) if raw else None


def download(url, target, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=180) as res, open(target, "wb") as out:
        shutil.copyfileobj(res, out)


def multipart_upload(url, fields, file_field, file_path):
    boundary = "----legado-sync-boundary"
    chunks = []
    for key, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode())
        chunks.append(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode())
        chunks.append(str(value).encode("utf-8"))
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}\r\n".encode())
    chunks.append(
        f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"\r\n'
        "Content-Type: application/vnd.android.package-archive\r\n\r\n".encode()
    )
    chunks.append(file_path.read_bytes())
    chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    data = b"".join(chunks)
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(req, timeout=180) as res:
        raw = res.read().decode("utf-8")
        return json.loads(raw) if raw else None


def github_release(args, tag):
    api = GITHUB_API.format(owner=args.github_owner, repo=args.github_repo)
    headers = {}
    if args.github_token:
        headers["Authorization"] = f"Bearer {args.github_token}"
    if tag:
        return request_json(f"{api}/releases/tags/{urllib.parse.quote(tag, safe='')}", headers=headers)
    return request_json(f"{api}/releases/latest", headers=headers)


def gitee_get_release(args, tag):
    api = GITEE_API.format(owner=args.gitee_owner, repo=args.gitee_repo)
    query = urllib.parse.urlencode({"access_token": args.gitee_token})
    try:
        return request_json(f"{api}/releases/tags/{urllib.parse.quote(tag, safe='')}?{query}")
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise


def gitee_upsert_release(args, tag, name, body, prerelease):
    api = GITEE_API.format(owner=args.gitee_owner, repo=args.gitee_repo)
    found = gitee_get_release(args, tag)
    payload = {
        "access_token": args.gitee_token,
        "tag_name": tag,
        "name": name or tag,
        "body": body or "",
        "prerelease": "true" if prerelease else "false",
        "target_commitish": args.target_commitish,
    }
    if found and found.get("id"):
        release_id = found["id"]
        return request_json(f"{api}/releases/{release_id}", method="PATCH", data=payload)
    return request_json(f"{api}/releases", method="POST", data=payload)


def gitee_clear_attachments(args, release_id):
    api = GITEE_API.format(owner=args.gitee_owner, repo=args.gitee_repo)
    query = urllib.parse.urlencode({"access_token": args.gitee_token})
    attachments = request_json(f"{api}/releases/{release_id}/attach_files?{query}") or []
    for item in attachments:
        attach_id = item.get("id")
        if attach_id:
            request_json(f"{api}/releases/{release_id}/attach_files/{attach_id}?{query}", method="DELETE")


def sync_one(args, tag, channel_tag=None):
    release = github_release(args, tag)
    source_tag = release["tag_name"]
    target_tag = channel_tag or source_tag
    assets = [
        item for item in release.get("assets", [])
        if item.get("name", "").lower().endswith(".apk")
    ]
    if not assets:
        raise RuntimeError(f"No APK assets found in GitHub release {source_tag}")
    print(f"Syncing GitHub release {source_tag} to Gitee {target_tag}")
    with tempfile.TemporaryDirectory(prefix="legado-gitee-sync-") as tmp:
        tmp_dir = Path(tmp)
        files = []
        headers = {}
        if args.github_token:
            headers["Authorization"] = f"Bearer {args.github_token}"
        for asset in assets:
            target = tmp_dir / asset["name"]
            download(asset["browser_download_url"], target, headers=headers)
            files.append(target)
        gitee_release = gitee_upsert_release(
            args,
            target_tag,
            release.get("name") or target_tag,
            release.get("body") or "",
            bool(release.get("prerelease", False)),
        )
        release_id = gitee_release.get("id")
        if not release_id:
            raise RuntimeError(f"Unable to resolve Gitee release id for {target_tag}")
        if args.clear:
            gitee_clear_attachments(args, release_id)
        api = GITEE_API.format(owner=args.gitee_owner, repo=args.gitee_repo)
        for path in files:
            print(f"Uploading {path.name}")
            multipart_upload(
                f"{api}/releases/{release_id}/attach_files",
                {"access_token": args.gitee_token},
                "file",
                path,
            )
    print(f"Finished Gitee sync: {target_tag}")


def main(argv):
    parser = argparse.ArgumentParser(description="Sync GitHub release APK assets to Gitee release.")
    parser.add_argument("--tag", default="", help="GitHub release tag. Empty means latest release.")
    parser.add_argument("--sync-channel", action="store_true", help="Also sync assets to update channel tag.")
    parser.add_argument("--channel-tag", default="latest-arm64-release")
    parser.add_argument("--github-owner", default="Rimchars")
    parser.add_argument("--github-repo", default="legado")
    parser.add_argument("--gitee-owner", default="zziji")
    parser.add_argument("--gitee-repo", default="legado")
    parser.add_argument("--target-commitish", default="master")
    parser.add_argument("--clear", action="store_true", default=True, help="Clear old Gitee attachments first.")
    parser.add_argument("--no-clear", dest="clear", action="store_false")
    parser.add_argument("--github-token", default=os.environ.get("GITHUB_TOKEN", ""))
    parser.add_argument("--gitee-token", default=os.environ.get("GITEE_TOKEN", ""))
    args = parser.parse_args(argv)
    if not args.gitee_token:
        raise SystemExit("GITEE_TOKEN is required. Set env GITEE_TOKEN or pass --gitee-token.")
    sync_one(args, args.tag or None)
    if args.sync_channel:
        sync_one(args, args.tag or None, args.channel_tag)


if __name__ == "__main__":
    main(sys.argv[1:])
