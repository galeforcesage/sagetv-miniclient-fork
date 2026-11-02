# FFmpeg patch series

The FFmpeg source is the official `n6.1.1` tag:

- tag object: `6f4048827982a8f48f71f551a6e1ed2362816eec`
- peeled commit: `e38092ef9395d7049f871ef4d5411eb410e283e0`

`0001-add-ijk-application-protocol-hooks.patch` contains the audited eight-file
IJK application callback and custom protocol integration. It is the only
source delta applied to official FFmpeg. The custom protocols are declared in
`libavformat/protocols.c` so FFmpeg configure includes them in its generated
`protocol_list.c`. IJK's private livehook demuxer is not ported because it
depends on FFmpeg-private demuxer structures and Sage playback uses the
`ijkmediadatasource` protocol.
