#!/usr/bin/env python3
"""Convert the Sorting reference-image L1 graph to an openTCS 7 plant model.

Requires PyYAML. Run from any directory; outputs Sorting.xml beside this script.
"""
from pathlib import Path
import math
import colorsys
import xml.etree.ElementTree as ET

import yaml


def element(parent, tag, **attrs):
    return ET.SubElement(parent, tag, {k: str(v) for k, v in attrs.items()})


def prop(parent, name, value):
    element(parent, 'property', name=name, value=value)


def main():
    folder = Path(__file__).resolve().parent
    settings = {}
    for line in (folder / 'simulation.properties').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            settings[key.strip()] = value.strip()
    fleet_size = int(settings['fleet.size'])
    if not 1 <= fleet_size <= 99:
        raise ValueError('fleet.size must be between 1 and 99')
    rotation = {
        'rotation.enabled': settings.get('rotation.enabled', 'true'),
        'rotation.maxVelocityDegPerSec': settings.get('rotation.maxVelocityDegPerSec', '90'),
        'rotation.maxAccelerationDegPerSec2': settings.get('rotation.maxAccelerationDegPerSec2', '180'),
        'rotation.maxDecelerationDegPerSec2': settings.get('rotation.maxDecelerationDegPerSec2', '180'),
    }
    for key, value in rotation.items():
        if key != 'rotation.enabled' and (not math.isfinite(float(value)) or float(value) <= 0):
            raise ValueError(f'{key} must be finite and positive')
    data = yaml.safe_load((folder / 'Sorting.building.yaml').read_text())
    if data['coordinate_system'] != 'reference_image' or set(data['levels']) != {'L1'}:
        raise ValueError('This converter expects the Sorting reference-image L1 map')
    level = data['levels']['L1']
    vertices, lanes = level['vertices'], level['lanes']
    scales = [m[2]['distance'][1] * 1000 / math.dist(vertices[m[0]][:2], vertices[m[1]][:2])
              for m in level['measurements']]
    scale = sum(scales) / len(scales)
    used = sorted({i for lane in lanes for i in lane[:2]})
    names = {i: vertices[i][3] or f'L1_P{i:03d}' for i in used}
    if len(set(names.values())) != len(names):
        raise ValueError('Duplicate point names')
    positions = {i: (round(vertices[i][0] * scale), round(-vertices[i][1] * scale),
                     round(level.get('elevation', 0) * 1000)) for i in used}
    root = ET.Element('model', version='7.0.0', name=data['name'])
    path_names = [f'L1_Path_{i:03d}' for i in range(len(lanes))]
    for i in used:
        vertex = vertices[i]
        attrs = vertex[4] if len(vertex) > 4 else {}
        x, y, z = positions[i]
        point = element(root, 'point', name=names[i], positionX=x, positionY=y, positionZ=z,
                        vehicleOrientationAngle='NaN',
                        type='PARK_POSITION' if attrs.get('is_parking_spot', [4, False])[1]
                        else 'HALT_POSITION')
        element(point, 'maxVehicleBoundingBox', length=1000, width=1000, height=1000,
                referenceOffsetX=0, referenceOffsetY=0)
        for n, lane in enumerate(lanes):
            if lane[0] == i:
                element(point, 'outgoingPath', name=path_names[n])
        prop(point, 'rmf:vertexIndex', i)
        if names[i].startswith('waite_'):
            wait_number = int(names[i].removeprefix('waite_'))
            prop(point, 'sorting:pickup', (wait_number - 1) // 4 + 1)
            prop(point, 'sorting:queuePosition', (wait_number - 1) % 4 + 1)
        for key, value in attrs.items():
            prop(point, 'rmf:' + key, str(value[1]).lower() if isinstance(value[1], bool) else value[1])
        element(point, 'pointLayout', labelOffsetX=-10, labelOffsetY=-20, layerId=0)
    for n, (a, b, attrs) in enumerate(lanes):
        speed = attrs.get('speed_limit', [3, 0])[1]
        velocity = round(speed * 1000) if speed > 0 else 1800
        path = element(root, 'path', name=path_names[n], sourcePoint=names[a],
                       destinationPoint=names[b], length=max(1, round(math.dist(positions[a], positions[b]))),
                       maxVelocity=velocity,
                       maxReverseVelocity=velocity if attrs['bidirectional'][1] else 0, locked='false')
        prop(path, 'rmf:laneIndex', n)
        element(path, 'pathLayout', connectionType='DIRECT', layerId=0)
    for i in range(1, fleet_size + 1):
        if f'init_{i}' not in names.values():
            raise ValueError(f'Missing initial point init_{i}')
        vehicle = element(root, 'vehicle', name=f'Sorting-AGV-{i:02d}',
                          energyLevelCritical=20, energyLevelGood=80,
                          energyLevelFullyRecharged=95, energyLevelSufficientlyRecharged=40,
                          maxVelocity=1800, maxReverseVelocity=1800)
        element(vehicle, 'boundingBox', length=500, width=500, height=400,
                referenceOffsetX=0, referenceOffsetY=0)
        for key, value in {
            'tcs:preferredAdapterClass': 'org.opentcs.commadapter.vehicle.vda5050.CommAdapterFactoryImpl',
            'vda5050:version': '2.0',
            'vda5050:interfaceName': 'uagv',
            'vda5050:manufacturer': 'SortingDemo',
            'vda5050:serialNumber': f'Sorting-AGV-{i:02d}',
            'vda5050:mapId': 'Sorting',
            'vda5050:maxStepsBase': '1',
            'vda5050:maxStepsHorizon': '0',
            'vda5050:vehicleLengthLoaded': '500',
            'vda5050:vehicleLengthUnloaded': '500',
            'sorting:initialPosition': f'init_{i}',
        }.items():
            prop(vehicle, key, value)
        rgb = colorsys.hsv_to_rgb((i - 1) / fleet_size, 0.8, 0.9)
        element(vehicle, 'vehicleLayout', color='#' + ''.join(f'{round(c * 255):02X}' for c in rgb))
    types = [('Pickup', 'Load cargo', 'LOAD_TRANSFER_GENERIC'),
             ('Dropoff', 'Unload cargo', 'LOAD_TRANSFER_GENERIC'),
             ('Recharge', 'CHARGE', 'RECHARGE_GENERIC')]
    for name, operation, symbol in types:
        kind = element(root, 'locationType', name=name)
        for op in (operation, 'NOP'):
            element(kind, 'allowedOperation', name=op)
        prop(kind, 'tcs:defaultLocationSymbol', symbol)
        prop(kind, 'tcs:defaultLocationTypeSymbol', symbol)
        element(kind, 'locationTypeLayout', locationRepresentation=symbol)
    for i in used:
        name = names[i]
        if name.startswith('pickup_'):
            kind, symbol = 'Pickup', 'LOAD_TRANSFER_GENERIC'
            dx, dy = -700, 0
        elif name.startswith(('dropoff_', 'droppff_')):
            kind, symbol = 'Dropoff', 'LOAD_TRANSFER_GENERIC'
            dx, dy = (700, 0) if vertices[i][0] == 2800 else (0, 700 if vertices[i][1] == 300 else -700)
        elif name.startswith('init_'):
            kind, symbol = 'Recharge', 'RECHARGE_GENERIC'
            dx, dy = 0, 700 if vertices[i][1] == 200 else -700
        else:
            continue
        x, y, z = positions[i]
        location = element(root, 'location', name='LOC_' + name, positionX=x + dx,
                           positionY=y + dy, positionZ=z, locked='false', type=kind)
        element(location, 'link', point=name)
        prop(location, 'tcs:defaultLocationSymbol', symbol)
        element(location, 'locationLayout', labelOffsetX=-10, labelOffsetY=-20,
                locationRepresentation=symbol, layerId=0)
    layout = element(root, 'visualLayout', name='Sorting-L1', scaleX=20.0, scaleY=20.0)
    element(layout, 'layer', id=0, ordinal=0, visible='true', name='L1', groupId=0)
    element(layout, 'layerGroup', id=0, name='Sorting', visible='true')
    prop(root, 'rmf:source', 'Sorting.building.yaml')
    prop(root, 'rmf:millimetersPerPixel', scale)
    ET.indent(root, space='    ')
    ET.ElementTree(root).write(folder / 'Sorting.xml', encoding='UTF-8', xml_declaration=True)
    print(f'Generated Sorting.xml: {len(used)} points, {len(lanes)} paths, '
          f'{len(root.findall("location"))} locations, {fleet_size} vehicles; {scale:g} mm/pixel')


if __name__ == '__main__':
    main()
