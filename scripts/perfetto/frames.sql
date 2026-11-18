SELECT a.ts, a.dur, a.jank_type, a.present_type, a.layer_name
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
WHERE p.name = '{{PACKAGE}}'
ORDER BY a.ts;
