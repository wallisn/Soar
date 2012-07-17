#!/usr/bin/env python
# -*- coding: utf-8 -*-

import argparse, glob, os, random, shutil, subprocess, sys, thread, time
import pp

g_dir = 'experiment'
g_plotter = './puddleworld.py'
g_rules = 'PuddleWorld/puddle-world.soar'

g_ep_tuples = []
g_ep_tuples.append((10, 10, -1, 0, 0))
g_ep_tuples.append((10, 20, -1, 0, 0))
g_ep_tuples.append((20, 10, -1, 0, 0))
g_ep_tuples.append((20, 20, -1, 0, 0))
g_ep_tuples.append((10, 10, 0, 20, 10))
g_ep_tuples.append((10, 10, 0, 10, 20))
g_ep_tuples.append((10, 10, 0, 20, 20))
g_ep_tuples.append((10, 10, 500, 20, 10))
g_ep_tuples.append((10, 10, 500, 10, 20))
g_ep_tuples.append((10, 10, 500, 20, 20))


parser = argparse.ArgumentParser(description='Run PuddleWorld experiments.')
parser.add_argument('-j', '--jobs', metavar='N', type=int,
                   action='store',
                   help='number of experiments to run in parallel')
parser.add_argument('-r', '--runs', metavar='N', type=int,
                   action='store', default=1,
                   help='number of runs per experiment')
parser.add_argument('-e', '--episodes', metavar='N', type=int,
                   action='store', default=1000,
                   help='number of episodes per run')

args = parser.parse_args()

if args.jobs is None:
  args.jobs = 'autodetect'


if not os.path.isdir(g_dir):
  os.mkdir(g_dir)
seeds = []
seeds_file = g_dir + '/seeds'
if os.path.isfile(seeds_file):
  f = open(seeds_file, 'r')
  for seed in f:
    seeds.append(int(seed))
  f.close()
if len(seeds) != args.runs:
  seeds = []
  for i in range(0, args.runs):
    seeds.append(random.randint(0,65535))
  f = open(seeds_file, 'w')
  for seed in seeds:
    f.write(str(seed) + '\n')
  f.close()
print str(seeds) + '\n'


class Experiment:
  def __init__(self, episodes, seed, rules, rl_rules_out, output, ep_tuple):
    self.episodes = episodes
    self.seed = seed
    self.rules = rules
    self.rl_rules_out = rl_rules_out
    self.output = output
    
    self.ep_tuple = ep_tuple
    self.div_x = ep_tuple[0]
    self.div_y = ep_tuple[1]
    self.sp_episode = ep_tuple[2]
    self.sp_div_x = ep_tuple[3]
    self.sp_div_y = ep_tuple[4]
  
  def get_args(self):
    return ['out/PuddleWorld',
            '--episodes', str(self.episodes),
            '--seed', str(self.seed),
            '--rules', str(self.rules),
            '--rl-rules-out', str(self.rl_rules_out),
            '--sp-special', str(self.sp_episode), str(self.sp_div_x), str(self.sp_div_y)]
  
  def print_args(self):
    args = self.get_args()
    cmd = ''
    for arg in args:
      cmd += arg + ' '
    cmd += '> ' + self.output
    print cmd
  
  def run(self):
    args = self.get_args()
    f = open(self.output, 'w')
    subprocess.call(args, stderr=subprocess.PIPE, stdout=f)
    f.close()
    return self


dirs = []
experiments = []
for ep_tuple in g_ep_tuples:
  dir = g_dir + '/' + str(ep_tuple[0]) + '-' + str(ep_tuple[1]) + '_' + str(ep_tuple[2]) + '_' + str(ep_tuple[3]) + '-' + str(ep_tuple[4])
  if not os.path.isdir(dir):
    os.mkdir(dir)
  dirs.append(dir)
  
  rules = dir + '/in.soar'
  shutil.copy(g_rules, rules)
  f = open(rules, 'a')
  f.write('sp {apply*initialize*puddleworld\n' +
          '    (state <s> ^operator.name puddleworld)\n' +
          '-->\n' +
          '    (<s> ^name puddleworld\n' +
          '        ^div <d>)\n' +
          '    (<d> ^name default\n' +
          '        ^x (/ 1.001 ' + str(ep_tuple[0]) + ')\n' +
          '        ^y (/ 1.001 ' + str(ep_tuple[1]) + '))\n' +
          '}\n');
  f.close()
  
  for seed in seeds:
    rl_rules_out = dir + '/out-' + str(seed) + '.soar'
    output = dir + '/puddleworld-' + str(seed) + '.out'
    experiment = Experiment(args.episodes, seed, rules, rl_rules_out, output, ep_tuple)
    experiments.append(experiment)


class Progress:
  def __init__(self, experiments):
    self.lock = thread.allocate_lock()
    
    self.count = {}
    self.finished = {}
    for experiment in experiments:
      try:
        self.count[experiment.ep_tuple] += 1
      except KeyError:
        self.count[experiment.ep_tuple] = 1
      self.finished[experiment.ep_tuple] = 0

  def just_finished(self, experiment):
    self.lock.acquire()
    self.finished[experiment.ep_tuple] += 1
    self.lock.release()

  def all_finished(self, ep_tuple):
    self.lock.acquire()
    num = self.count[ep_tuple]
    fin = self.finished[ep_tuple]
    self.lock.release()
    return fin is num

job_server = pp.Server(args.jobs)
progress = Progress(experiments)
start_time = time.time()
jobs = [(job_server.submit(Experiment.run, (experiment,), (), ('subprocess', 'thread',), callback=progress.just_finished, group=experiment.ep_tuple)) for experiment in experiments]

for ep_tuple, dir in zip(g_ep_tuples, dirs):
  while True:
    job_server.print_stats()
    if progress.all_finished(ep_tuple):
      break
    else:
      time.sleep(5)
  job_server.wait(ep_tuple)
  args = [g_plotter] + glob.glob(dir + '/*.out')
  print 'Plotting data for ' + str(ep_tuple) + '\n'
  subprocess.call(args, stderr=subprocess.PIPE, stdout=subprocess.PIPE)

print 'Total time elapsed: ', time.time() - start_time, 'seconds'