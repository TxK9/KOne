# KOne management backend

Python 3.12; standard library only.

```sh
python -B server.py --host 127.0.0.1 --port 8768 --data-dir var
python -B -m unittest discover -s tests -p "test_*.py"
python -B deploy/build_web.py --esbuild PATH_TO_ESBUILD
python -B deploy/package.py --output OUTPUT_DIRECTORY
```
