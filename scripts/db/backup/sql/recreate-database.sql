SELECT format('DROP DATABASE IF EXISTS %I', :'database_name');
\gexec
SELECT format('CREATE DATABASE %I', :'database_name');
\gexec
