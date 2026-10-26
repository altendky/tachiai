"""Fixed public region/catalogue operations, loaded after canonical bootstrap."""
import base64
import json
import multiprocessing
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import sys

bootstrap = sys.modules["web_abema_bootstrap"]
Refused = bootstrap.Refused
PAGE = "https://abema.tv/now-on-air/abema-news"
REGIONS = {"GLOBAL": "https://ds-glb-linear-abematv.akamaized.net/region",
           "DOMESTIC": "https://ds-linear-abematv.akamaized.net/region"}
LIMIT, REQUEST_SECONDS = 512 * 1024, 10


def extract_region(raw):
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= LIMIT: raise Refused()
    text = raw.decode('utf8', errors='strict')
    matches = list(re.finditer(r'window\.__CLIENT_REGION__\s*=\s*', text))
    if len(matches) != 1: raise Refused()
    candidate = text[matches[0].end():matches[0].end() + 8192]
    if not candidate.startswith('{'): raise Refused()
    _, end = json.JSONDecoder().raw_decode(candidate)
    if not re.match(r'\s*;', candidate[end:]): raise Refused()
    region = bootstrap.strict_json(candidate[:end].encode(), 8192, max_nodes=512, max_depth=8)
    if (not isinstance(region, dict) or type(region.get('isAllowed')) is not bool or
        type(region.get('status')) is not bool or not isinstance(region.get('isoCountryCode'), str) or
        not re.fullmatch(r'[A-Z]{2}', region['isoCountryCode']) or type(region.get('divisionCode')) is not int or
        region['divisionCode'] not in {0, 1} or region.get('cdnURL') not in REGIONS.values()):
        raise Refused()
    return region


def request_spec(operation, fields):
    if not isinstance(fields, dict): raise Refused()
    headers = {'Accept': 'application/json', 'Accept-Encoding': 'identity'}
    if operation == 'REGION_PAGE' and fields == {}:
        url = PAGE; headers['Accept'] = 'text/html'
    elif operation == 'SELECTED_REGION' and set(fields) == {'selected'} and fields['selected'] in REGIONS:
        url = REGIONS[fields['selected']]; headers['Accept'] = '*/*'
    elif operation == 'MODERN_CHANNELS' and set(fields) == {'query'}:
        query = fields['query']
        if (not isinstance(query, dict) or query not in ({}, {'division': 1}) or
                any(type(value) is not int for value in query.values())): raise Refused()
        url = 'https://api.p-c3-e.abema-tv.com/v1/channels'
        if query: url += '?' + urllib.parse.urlencode(query)
    else: raise Refused()
    return urllib.request.Request(url, method='GET', headers=headers)


def exchange(operation, fields, opener):
    request = request_spec(operation, fields)
    try: reply = opener.open(request, timeout=REQUEST_SECONDS)
    except urllib.error.HTTPError as error: reply = error
    with reply:
        status = reply.status
        if type(status) is not int or not 100 <= status <= 599: raise Refused()
        if operation == 'SELECTED_REGION' or status != 200: return status, b''
        expected = 'text/html' if operation == 'REGION_PAGE' else 'application/json'
        if (reply.headers.get('Content-Type', '').split(';', 1)[0].strip().lower() != expected or
                reply.headers.get('Content-Encoding', 'identity').lower() != 'identity'): raise Refused()
        length = reply.headers.get('Content-Length')
        if length is not None and (not length.isascii() or not length.isdecimal() or int(length) > LIMIT): raise Refused()
        chunks, size = [], 0
        while True:
            chunk = reply.read1(min(8192, LIMIT + 1 - size))
            if not chunk: break
            size += len(chunk)
            if size > LIMIT: raise Refused()
            chunks.append(chunk)
        body = b''.join(chunks)
        if length is not None and len(body) != int(length): raise Refused()
        return status, body


def worker(sender, operation, fields):
    try:
        with open(os.devnull, 'wb') as silent:
            os.dup2(silent.fileno(), 1); os.dup2(silent.fileno(), 2)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), bootstrap.NoRedirect())
        status, body = exchange(operation, fields, opener)
        sender.send_bytes(json.dumps({'status': status, 'body': base64.b64encode(body).decode('ascii')}).encode('ascii'))
    except BaseException:
        try: sender.send_bytes(b'{"refused":true}')
        except BaseException: pass
    finally: sender.close()


class MetadataTransport:
    """Closed public metadata only; parent hard timeout also bounds trickle reads."""
    def __init__(self, context=None):
        self.context, self.used = context or multiprocessing.get_context('spawn'), set()

    def request(self, operation, fields, timeout):
        request_spec(operation, fields)
        if operation in self.used or timeout <= 0: raise Refused()
        self.used.add(operation)
        receiver, sender = self.context.Pipe(duplex=False)
        process = self.context.Process(target=worker, args=(sender, operation, fields), daemon=True)
        started = False
        try:
            with bootstrap.completing_cleanup():
                process.start(); started = True; sender.close()
            if not receiver.poll(min(timeout, REQUEST_SECONDS)): raise Refused()
            value = bootstrap.strict_json(receiver.recv_bytes(bootstrap.IPC_LIMIT), bootstrap.IPC_LIMIT)
            if (not isinstance(value, dict) or set(value) != {'status', 'body'} or
                    type(value['status']) is not int or not 100 <= value['status'] <= 599 or
                    not isinstance(value['body'], str)): raise Refused()
            body = base64.b64decode(value['body'], validate=True)
            if len(body) > LIMIT or (operation == 'SELECTED_REGION' or value['status'] != 200) and body: raise Refused()
            return value['status'], body
        finally:
            with bootstrap.completing_cleanup():
                sender.close(); receiver.close()
                if started:
                    process.join(0.2)
                    if process.is_alive(): process.terminate(); process.join(2)
                    if process.is_alive(): process.kill(); process.join(2)
                    if process.is_alive(): raise bootstrap.CleanupUnconfirmed()
                process.close()
