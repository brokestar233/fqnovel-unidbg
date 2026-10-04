echo ===INIT_COUNT===
docker logs fqnovel-unidbg 2>&1 | grep -c "IdleFQ初始化完成"
echo ===RESET_EVENTS===
docker logs fqnovel-unidbg 2>&1 | grep -E "重置|三联|恢复" | tail -10
echo ===POOL_CREATE===
docker logs fqnovel-unidbg 2>&1 | grep -E "线程池|池大小" | tail -6
