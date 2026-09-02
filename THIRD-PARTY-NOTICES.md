# Third-party notices

Stereo Analog Recorder distributes the following third-party software.
License texts are reproduced below as required by each license.

---

## tinymix / libtinyalsa

- **Upstream**: https://github.com/tinyalsa/tinyalsa
- **Version**: tinyalsa 2.0.0, commit `9fab97c` (master, 2026-07-27)
- **Distribution form**: prebuilt ELF binaries for `arm64-v8a`, `armeabi-v7a`,
  `x86_64`, and `x86` under `dependencies/tinymix/<arch>/tinymix`, plus the
  unmodified source archive `dependencies/src/tinyalsa-master.zip` for users
  who want to rebuild from source.
- **License**: BSD 3-Clause "New" / "Revised" License
- **Copyright**: Copyright 2011, The Android Open Source Project.
  Copyright (c) 2019, The Linux Foundation.

### BSD 3-Clause License (full text)

Copyright 2011, The Android Open Source Project
Copyright (c) 2019, The Linux Foundation.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of The Android Open Source Project nor the names of
      its contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY The Android Open Source Project ``AS IS'' AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL The Android Open Source Project BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
DAMAGE.

Upstream license file: https://github.com/tinyalsa/tinyalsa/blob/master/LICENSE

---

## AndroidX libraries (informational)

Stereo Analog Recorder uses the following **unmodified** AndroidX libraries,
all licensed under the Apache License, Version 2.0:

| Library | Version |
| --- | --- |
| `androidx.core:core-ktx` | 1.12.0 |
| `androidx.appcompat:appcompat` | 1.6.1 |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 |
| `androidx.activity:activity-ktx` | 1.8.2 |

The Apache 2.0 `LICENSE.txt` and `NOTICE.txt` for each library are shipped
inside the AAR that Gradle downloads, and end up in the built APK under
`META-INF/`. No additional attribution is required at the application level
because Stereo Analog Recorder distributes these libraries unmodified.

Full Apache 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

---

## Google Material Components for Android (informational)

`com.google.android.material:material:1.11.0`, licensed under the Apache
License, Version 2.0. As with the AndroidX libraries above, the upstream
`LICENSE.txt` and `NOTICE.txt` files are shipped inside the AAR and copied
into the APK at build time. See
https://github.com/material-components/material-components-android/blob/master/LICENSE
for the full text.

