select col0, col1, col2, col3, sum(col4) as total, avg(col5) from base group by col0, col3, cube (col1, col2) having total > 100