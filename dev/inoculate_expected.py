# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
"""Write pyshacl's inoculate() output for an ontology as N-Triples.

Usage: python3 dev/inoculate_expected.py ONTOLOGY.ttl OUTPUT.nt

The triples are those pyshacl copies into an empty data graph. Tested with
pyshacl 0.40.1 and rdflib 7.6.0.
"""
import sys

import rdflib
from pyshacl.rdfutil.inoculate import inoculate

ontology = rdflib.Graph().parse(sys.argv[1], format="turtle")
data = inoculate(rdflib.Graph(), ontology)
data.serialize(sys.argv[2], format="nt", encoding="utf-8")
