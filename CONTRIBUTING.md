# Contributing to Vespa
Contributions to [Vespa](http://github.com/vespa-engine/vespa)
and the [Vespa documentation](http://github.com/vespa-engine/documentation)
are welcome.
This documents tells you what you need to know to contribute.

## Open development
All work on Vespa happens directly on Github,
using the [Github flow model](https://guides.github.com/introduction/flow/).
We release the master branch a few times a week and you should expect it to almost always work.
In addition to the [public Travis build](https://travis-ci.org/vespa-engine/vespa) 
we have a large acceptance and performance test suite which
is also run continuously. We plan to add this to the open source code base later.

All pull requests are reviewed by a member of the 
[Vespa committers](https://github.com/orgs/vespa-engine/teams/vespa/members) team, regardless
of who made it. If you want to become a committer, making some quality contributions is the way to start.

## Versioning
Vespa uses semantic versioning - see
[vespa versions](http://docs.vespa.ai/documentation/vespa-versions.html).
Notice in particular that any Java API in a package having a @PublicAPI
annotation in the package-info file cannot be changed in an incompatible way
between major versions: Existing types and method signatures must be preserved
(but can be marked deprecated).

## Issues
We track issues in [GitHub issues](https://github.com/vespa-engine/vespa/issues).
It is fine to submit issues also for feature requests and ideas, whether or not you intend to work on them.

There is also a [ToDo list](TODO.md) for larger things which nobody are working on yet.

### Getting started
See [README](README.md) for how to build and test Vespa. 

Vespa is large and getting an overview of the code can be a challenge.
It may help to read the READMEs of each module.

## License and copyright
If you add new files you are welcome to use your own copyright.
In any case the code (or documentation) you submit will be licensed
under the Apache 2.0 license.