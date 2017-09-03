meta:
  id: test
  license: MIT
  encoding: UTF-8
  endian: le

seq:
- id: v1
  type: u1
- id: v2
  size: 8+(1==2).to_i
  type:
    switch-on: v1
    cases:
      1: strz
- id: v3
  type: type1

types:

  type1:
    seq:
      - id: x1
        type: u4
    instances:
      key:
        pos: 9
        size: 5
        type:
          switch-on: x1
          cases:
            2: type2
            3: type3

  type2:
    seq:
      - id: x2
        type: u2
  type3:
    seq:
      - id: x3
        type: u2
