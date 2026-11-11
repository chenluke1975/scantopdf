# Only Android platform APIs are used. Page filters are stored by enum name in session.json,
# so keep the enum constant names stable across releases.
-keepclassmembers enum io.github.chenluke1975.scantopdf.Filter { *; }
