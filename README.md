# IvyTrigger

IvyTrigger provides polling mechanisms to poll an Ivy file and triggers a build if an Ivy dependency version has changed.

![](docs/images/ivy_trigger.png)

# Features

The plugin makes it possible to monitor the dependencies of an Ivy descriptor.
For example, if a dependency has the following revision 'latest.release' or '2.+' and a new artifact has been deployed in the repository manager (managed by Ivy), a new build is scheduled.

Note: The plugin uses only persistence in memory.
There is no impact on the Jenkins infrastructure (no new files created).
