WITH file_commits AS (
    SELECT DISTINCT gcf.file_path, gc.id, gc.hash, gc.author, gc.committed_at
    FROM git_commit_files gcf
    JOIN git_commits gc ON gcf.commit_id = gc.id
    WHERE gc.committed_at >= :since
), ranked AS (
    -- Hash breaks timestamp ties consistently across index rebuilds.
    SELECT *, ROW_NUMBER() OVER (
        PARTITION BY file_path ORDER BY committed_at DESC, hash DESC
    ) AS recency
    FROM file_commits
), stats AS (
    SELECT file_path, COUNT(*) AS commit_count,
           MAX(committed_at) AS last_modified,
           MAX(CASE WHEN recency = 1 THEN author END) AS last_author,
           MIN(committed_at) AS first_commit,
           COUNT(DISTINCT author) AS distinct_authors
    FROM ranked
    GROUP BY file_path
)
SELECT 0 AS id, stats.*, gfs.class_id
FROM stats
LEFT JOIN git_file_stats gfs ON stats.file_path = gfs.file_path
ORDER BY stats.commit_count DESC, stats.file_path
LIMIT :limit
