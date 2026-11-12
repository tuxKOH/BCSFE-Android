#!/usr/bin/env python3
"""Prepare separately installed, debuggable Battle Cats splits for emulator QA.

Uses BCData's tbctk dependency to edit the base manifest. Preserves the original
split resource tables (merging modern sparse resource tables can corrupt them).
Does not install, change native code, or include any account/save data.
"""
from pathlib import Path
import argparse
import struct
import subprocess
import tempfile
import zipfile


def chunks(data, start):
    while start < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, start)
        if size < header or not size or start + size > len(data):
            raise ValueError('Invalid Android resource chunk')
        yield start, kind, header, size
        start += size


def replace_strings(data, old, new):
    """Rewrite only exact package strings in the manifest string pool."""
    result = bytearray(data[:8])
    for pos, kind, header, size in chunks(data, 8):
        raw = data[pos:pos+size]
        if kind != 1:
            result.extend(raw)
            continue
        count, styles, flags, start, style_start = struct.unpack_from('<IIIII', raw, 8)
        utf8 = bool(flags & 0x100)
        offsets = struct.unpack_from('<' + 'I'*count, raw, header)
        def length(at, width):
            v = int.from_bytes(raw[at:at+width], 'little'); at += width
            if v & (1 << (width*8-1)):
                v = ((v & ((1 << (width*8-1))-1)) << (width*8)) | int.from_bytes(raw[at:at+width], 'little')
                at += width
            return v, at
        def encoded_length(v, width):
            high = 1 << (width*8-1)
            return ((high | (v >> (width*8))).to_bytes(width, 'little') +
                    (v & ((1 << (width*8))-1)).to_bytes(width, 'little')) if v >= high else v.to_bytes(width, 'little')
        strings = bytearray(); new_offsets=[]
        for offset in offsets:
            at = start + offset
            if utf8:
                _, at = length(at, 1); n, at = length(at, 1)
                value = raw[at:at+n].decode('utf-8')
            else:
                n, at = length(at, 2); value = raw[at:at+n*2].decode('utf-16-le')
            if value == old:
                value = new
            new_offsets.append(len(strings))
            if utf8:
                b = value.encode('utf-8'); strings += encoded_length(len(value.encode('utf-16-le'))//2, 1) + encoded_length(len(b), 1) + b + b'\0'
            else:
                b = value.encode('utf-16-le'); strings += encoded_length(len(b)//2, 2) + b + b'\0\0'
        strings += b'\0' * (-len(strings) % 4)
        style_data = raw[style_start:] if style_start else b''
        pool = bytearray(raw[:start]); pool.extend(strings); pool.extend(style_data)
        struct.pack_into('<I', pool, 4, len(pool))
        struct.pack_into('<I', pool, 16, flags & ~1)  # rebuilt pool need not be sorted
        struct.pack_into('<I', pool, 24, start+len(strings) if style_start else 0)
        for i, offset in enumerate(new_offsets): struct.pack_into('<I', pool, header+i*4, offset)
        result.extend(pool)
    struct.pack_into('<I', result, 4, len(result))
    return result


def sort_attributes(data):
    """Android looks up attribute resource IDs using their sorted order."""
    b = bytearray(data); ids=[]
    for pos, kind, header, size in chunks(b, 8):
        if kind == 0x180:
            ids = struct.unpack_from('<'+'I'*((size-header)//4), b, pos+header)
        elif kind == 0x102:
            start, width, count = struct.unpack_from('<HHH', b, pos+24)
            at = pos+16+start
            attrs = [bytes(b[at+i*width:at+(i+1)*width]) for i in range(count)]
            def key(a):
                idx=struct.unpack_from('<I', a, 4)[0]
                return ids[idx] if idx < len(ids) else 0
            # idIndex/classIndex/styleIndex are one based attribute positions.
            special = struct.unpack_from('<HHH', b, pos+30)
            moved = sorted(enumerate(attrs), key=lambda pair: key(pair[1]))
            struct.pack_into('<HHH', b, pos+30, *(next(j+1 for j,(i,_) in enumerate(moved) if i==v-1) if v else 0 for v in special))
            b[at:at+count*width] = b''.join(a for _,a in moved)
    return b


def resource_package(data, name):
    b = bytearray(data)
    for pos, kind, _, _ in chunks(b, struct.unpack_from('<H', b, 2)[0]):
        if kind == 0x200:
            text = name.encode('utf-16-le')
            if len(text) > 254: raise ValueError('Package name too long')
            b[pos+12:pos+268] = text + b'\0'*(256-len(text))
    return b


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('xapk', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--region', choices=('jp','tw','en','kr'), required=True)
    p.add_argument('--version', required=True); p.add_argument('--tbctk', type=Path, default=Path('/tmp/tbctk'))
    p.add_argument('--sdk', type=Path, default=Path.home()/'Android/Sdk')
    a=p.parse_args(); a.output.mkdir(parents=True, exist_ok=False)
    package='jp.co.ponos.battlecats'+('' if a.region=='jp' else a.region)
    qa_package=package+'.bcsfeqa'
    # The wrapper lives outside this repository, in the user's tbctk checkout.
    example=a.tbctk/'tbctk-apk/examples/bcsfe_qa.rs'
    example.parent.mkdir(exist_ok=True)
    example.write_text('''use std::{path::PathBuf, str::FromStr};
use tbctk_apk::{BCApk,traits::Package};
use tbctk_common::{country_code::CountryCode,game_version::GameVersion};
fn main()->Result<(),Box<dyn std::error::Error>> {
 let a:Vec<String>=std::env::args().collect();
 let mut p=BCApk::new(&PathBuf::from(&a[1]),CountryCode::from_str(&a[2])?,GameVersion::from_str(&a[3])?)?;
 p.zip_mut().set_debuggable(true)?;p.zip_mut().set_package_name(a[4].clone())?;
 std::fs::write(&a[5],p.zip_mut().file("AndroidManifest.xml").unwrap())?;Ok(())
}
''')
    build=sorted((a.sdk/'build-tools').iterdir(),key=lambda x:tuple(int(n) for n in x.name.split('.') if n.isdigit()))[-1]
    with tempfile.TemporaryDirectory(prefix='bcsfe-game-qa-') as temp:
        temp=Path(temp)
        with zipfile.ZipFile(a.xapk) as z:
            apks=[]
            for name in z.namelist():
                if name.endswith('.apk'):
                    path=temp/Path(name).name; path.write_bytes(z.read(name)); apks.append(path)
        base=next(path for path in apks if path.name==package+'.apk')
        manifest=temp/'manifest.bin'
        subprocess.run(['cargo','run','--quiet','--release','-p','tbctk-apk','--example','bcsfe_qa','--',str(base),a.region,a.version,qa_package,str(manifest)],cwd=a.tbctk,check=True)
        for apk in apks:
            unsigned=temp/('unsigned-'+apk.name); aligned=temp/('aligned-'+apk.name)
            with zipfile.ZipFile(apk) as src,zipfile.ZipFile(unsigned,'w') as dst:
                for info in src.infolist():
                    if info.filename.startswith('META-INF/'): continue
                    raw=src.read(info)
                    if info.filename=='AndroidManifest.xml':
                        raw=sort_attributes(manifest.read_bytes()) if apk==base else replace_strings(raw,package,qa_package)
                    elif info.filename=='resources.arsc': raw=resource_package(raw,qa_package)
                    dst.writestr(info,raw)
            subprocess.run([str(build/'zipalign'),'-f','16384',str(unsigned),str(aligned)],check=True)
            subprocess.run([str(build/'apksigner'),'sign','--ks',str(Path.home()/'.android/debug.keystore'),
                            '--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android',
                            '--out',str(a.output/apk.name),str(aligned)],check=True)
    print('QA package:',qa_package)
    print('Install all generated APKs together using adb install-multiple -r --no-incremental.')


if __name__=='__main__': main()
