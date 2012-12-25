test:
	redis-server --daemonize yes --port 6381 --requirepass "password" --pidfile /tmp/redis-test.pid
	mvn clean compile test
	kill `cat /tmp/redis-test.pid`

.PHONY: test
